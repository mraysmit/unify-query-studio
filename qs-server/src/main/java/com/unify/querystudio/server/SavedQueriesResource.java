package com.unify.querystudio.server;

import com.unify.querystudio.compiler.CompilationException;
import com.unify.querystudio.compiler.CompiledQuery;
import com.unify.querystudio.compiler.Drift;
import com.unify.querystudio.compiler.QueryCompiler;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;

import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Saved queries (technical design, saved queries): canonical SQL plus layout, a new version on
 * every save, refused when saved over a newer version. Private to the owner until sharing exists.
 */
@Path("/api/queries")
public class SavedQueriesResource {

    /**
     * A save: the version it was edited from ({@code baseVersion}) is required when updating.
     * {@code description} is the Description query property.
     */
    public record SaveRequest(String name, String connectionId, String sql, Map<String, Object> layout,
                              Integer baseVersion, String description) {
    }

    public record Saved(String id, int version) {
    }

    /**
     * A saved version. {@code drift} is filled when a query is opened: how the tables and fields it
     * uses have changed since it was saved; null in version lists.
     */
    public record QueryView(String id, String name, String description, String connectionId, int version, String sql,
                            Map<String, Object> layout, String author, Instant savedAt, List<Drift.Change> drift) {

        QueryView withDrift(List<Drift.Change> drift) {
            return new QueryView(id, name, description, connectionId, version, sql, layout, author, savedAt, drift);
        }
    }

    public record Summary(String id, String name, String description, String connectionId, int version) {
    }

    public record Conflict(String message, QueryView current) {
    }

    @Inject
    SavedQueryStore store;

    @Inject
    TargetDatabases databases;

    @Inject
    Catalogs catalogs;

    @Inject
    SecurityIdentity identity;

    @POST
    public Response create(SaveRequest request) throws SQLException {
        require(request);
        CompiledQuery compiled = compile(request.connectionId(), request.sql());
        UUID id = store.create(me(), request.name(), request.description(), request.connectionId(), request.sql(),
                request.layout(), compiled.references());
        return Response.status(201).entity(new Saved(id.toString(), 1)).build();
    }

    @GET
    public List<Summary> list() throws SQLException {
        return store.list(me());
    }

    @GET
    @Path("{id}")
    public QueryView get(@PathParam("id") String id) throws SQLException {
        UUID uuid = uuid(id);
        QueryView view = store.latest(uuid, me()).orElseThrow(NotFoundException::new);
        TargetDatabases.Target target = databases.target(view.connectionId());
        return view.withDrift(Drift.check(store.latestReferences(uuid, me()), catalogs.withSavedQueries(target)));
    }

    @PUT
    @Path("{id}")
    public Response update(@PathParam("id") String id, SaveRequest request) throws SQLException {
        UUID uuid = uuid(id);
        String connectionId = store.connectionOf(uuid, me()).orElseThrow(NotFoundException::new);
        require(request);
        if (request.baseVersion() == null) {
            throw new BadRequestException("baseVersion is required: the version this edit started from.");
        }
        CompiledQuery compiled = compile(connectionId, request.sql());
        return switch (store.update(uuid, me(), request.baseVersion(), request.name(), request.description(),
                request.sql(), request.layout(), compiled.references())) {
            case SavedQueryStore.Saved s -> Response.ok(new Saved(id, s.version())).build();
            case SavedQueryStore.Stale s -> Response.status(409).entity(
                    new Conflict("Someone saved a newer version since you opened this query.", s.current())).build();
            case SavedQueryStore.NotFound n -> throw new NotFoundException();
        };
    }

    @GET
    @Path("{id}/versions")
    public List<QueryView> versions(@PathParam("id") String id) throws SQLException {
        List<QueryView> versions = store.versions(uuid(id), me());
        if (versions.isEmpty()) {
            throw new NotFoundException();
        }
        return versions;
    }

    private void require(SaveRequest request) {
        if (request == null || request.name() == null || request.name().isBlank() || request.sql() == null
                || request.connectionId() == null) {
            throw new BadRequestException("A saved query needs a name, a connection and SQL.");
        }
    }

    /** The SQL must compile on a connection the user may use: 404 if they may not, 422 with diagnostics if invalid. */
    private CompiledQuery compile(String connectionId, String sql) throws SQLException {
        TargetDatabases.Target target = databases.target(connectionId);
        try {
            return new QueryCompiler(catalogs.withSavedQueries(target), target.dialect()).compile(sql);
        } catch (CompilationException e) {
            throw new WebApplicationException(Response.status(422).entity(new RunsResource.Failure(e.getMessage(),
                    e.diagnostics().stream().map(DiagnosticJson::sql).toList())).build());
        }
    }

    /** A malformed ID is simply not found. */
    private static UUID uuid(String id) {
        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            throw new NotFoundException();
        }
    }

    private String me() {
        return identity.getPrincipal().getName();
    }
}
