package com.unify.querystudio.server;

import com.unify.querystudio.compiler.CompilationException;
import com.unify.querystudio.compiler.CompiledQuery;
import com.unify.querystudio.compiler.ParameterException;
import com.unify.querystudio.compiler.QueryCompiler;
import com.unify.querystudio.execution.QueryCancelledException;
import com.unify.querystudio.execution.QueryFailedException;
import com.unify.querystudio.execution.QueryRunner;
import com.unify.querystudio.execution.QueryTimeoutException;
import com.unify.querystudio.execution.RunHandle;
import com.unify.querystudio.execution.RunResult;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.core.Response;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Runs canonical SQL, or a saved query by ID and version: compiled again on every run (no earlier
 * compile is trusted), executed read-only within the configured bounds, and cancellable by the user
 * who started it.
 */
@Path("/api/connections/{id}/runs")
public class RunsResource {

    /**
     * Either canonical SQL or a saved query (its ID, and a version or {@code null} for the latest).
     *
     * @param runId           chosen by the client (a UUID), so it can cancel the run while it executes
     * @param parameterValues a value for each named parameter, by name without its colon
     */
    public record RunRequest(String runId, String sql, String queryId, Integer version, Integer maxRows,
                             Map<String, Object> parameterValues) {
    }

    public record Column(String name, String type) {
    }

    public record RunResponse(String runId, List<Column> columns, List<List<Object>> rows, boolean truncated,
                              long durationMs) {
    }

    /** Why a run produced no result. */
    public record Failure(String message, List<DiagnosticJson> diagnostics) {
    }

    @Inject
    TargetDatabases databases;

    @Inject
    Catalogs catalogs;

    @Inject
    Runs runs;

    @Inject
    QueryStudioConfig config;

    @Inject
    SecurityIdentity identity;

    @Inject
    AuditLog audit;

    @Inject
    SavedQueryStore savedQueries;

    /** Every attempt is audited, whatever its outcome. */
    @POST
    public Response run(@PathParam("id") String id, RunRequest request) throws SQLException {
        String user = identity.getPrincipal().getName();
        String runId = request != null && isUuid(request.runId()) ? request.runId() : null;
        UUID queryId = request == null ? null : uuidOrNull(request.queryId());
        AuditLog.Attempt attempt = new AuditLog.Attempt(user, id, runId, request == null ? null : request.sql(),
                queryId, request == null ? null : request.version());
        TargetDatabases.Target target;
        try {
            target = databases.runnable(id);
        } catch (NotFoundException | ForbiddenException e) {
            audit.run(attempt, null, null, null, "DENIED", null);
            throw e;
        }
        if (runId == null || (request.sql() == null) == (request.queryId() == null)) {
            throw new BadRequestException("A run needs a UUID run ID and either SQL or a saved query ID, not both.");
        }
        if (request.queryId() != null) {
            // A saved query that is not the user's, not on this connection, or has no such version is not found.
            Optional<SavedQueriesResource.QueryView> saved = queryId == null ? Optional.empty()
                    : savedQueries.version(queryId, user, request.version()).filter(q -> q.connectionId().equals(id));
            if (saved.isEmpty()) {
                audit.run(attempt, null, null, null, "DENIED", "Saved query not found");
                throw new NotFoundException();
            }
            attempt = attempt.withSavedQuery(saved.get().sql(), queryId, saved.get().version());
        }
        return run(target, attempt, request.maxRows(),
                request.parameterValues() == null ? Map.of() : request.parameterValues());
    }

    private Response run(TargetDatabases.Target target, AuditLog.Attempt attempt, Integer requestedRows,
                         Map<String, Object> parameterValues) throws SQLException {
        CompiledQuery query;
        try {
            query = new QueryCompiler(catalogs.withSavedQueries(target), target.dialect()).compile(attempt.canonicalSql());
        } catch (CompilationException e) {
            audit.run(attempt, null, null, null, "INVALID", e.getMessage());
            return failure(422, e.getMessage(), e.diagnostics().stream().map(DiagnosticJson::sql).toList());
        } catch (SQLException e) {
            audit.run(attempt, null, null, null, "UNREACHABLE", null);
            throw e;
        }
        try {
            query = query.bind(parameterValues);
        } catch (ParameterException e) {
            audit.run(attempt, null, null, null, "INVALID", e.getMessage());
            return failure(422, e.getMessage(), List.of());
        }
        int limit = config.run().maxRows();
        int maxRows = requestedRows == null ? limit : Math.clamp(requestedRows, 0, limit);
        String runId = attempt.runId();
        long start = System.nanoTime();
        try (Connection connection = databases.open(target)) {
            RunHandle handle = new QueryRunner(target.dialect()).start(connection, query, maxRows, config.run().timeout());
            if (!runs.register(runId, attempt.user(), handle)) {
                return failure(409, "A run with this ID is already in progress.", List.of());
            }
            try {
                RunResult result = handle.await();
                audit.run(attempt, query.targetSql(), result.rows().size(), result.elapsed(), "OK", null);
                return Response.ok(response(runId, result)).build();
            } finally {
                runs.finished(runId);
            }
        } catch (QueryCancelledException e) {
            audit.run(attempt, query.targetSql(), null, since(start), "CANCELLED", null);
            return failure(409, "The run was cancelled.", List.of());
        } catch (QueryTimeoutException e) {
            audit.run(attempt, query.targetSql(), null, since(start), "TIMEOUT", null);
            return failure(504, "The query took longer than " + config.run().timeout().toSeconds() + " seconds and was stopped.", List.of());
        } catch (QueryFailedException e) {
            audit.run(attempt, query.targetSql(), null, since(start), "FAILED", e.getCause().getMessage());
            return failure(502, "The database could not run the query: " + e.getCause().getMessage(), List.of());
        } catch (SQLException e) {
            audit.run(attempt, query.targetSql(), null, since(start), "UNREACHABLE", null);
            throw e;
        }
    }

    private static Duration since(long start) {
        return Duration.ofNanos(System.nanoTime() - start);
    }

    private static RunResponse response(String runId, RunResult result) {
        List<Column> columns = result.columns().stream()
                .map(c -> new Column(c.name(), c.type().getSqlTypeName().getName())).toList();
        List<List<Object>> rows = result.rows().stream()
                .map(row -> row.stream().map(JsonValues::of).toList()).toList();
        return new RunResponse(runId, columns, rows, result.truncated(), result.elapsed().toMillis());
    }

    private static Response failure(int status, String message, List<DiagnosticJson> diagnostics) {
        return Response.status(status).entity(new Failure(message, diagnostics)).build();
    }

    private static UUID uuidOrNull(String s) {
        return isUuid(s) ? UUID.fromString(s) : null;
    }

    private static boolean isUuid(String s) {
        try {
            return s != null && UUID.fromString(s).toString().equalsIgnoreCase(s);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
