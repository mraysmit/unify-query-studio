package com.unify.querystudio.server;

import com.unify.querystudio.compiler.CompilationException;
import com.unify.querystudio.compiler.QueryCompiler;
import com.unify.querystudio.design.Canvas;
import com.unify.querystudio.design.DesignCompiler;
import com.unify.querystudio.design.DesignException;
import com.unify.querystudio.design.DesignToSql;
import com.unify.querystudio.design.DesignView;
import com.unify.querystudio.compiler.SavedQuerySchema;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.inject.Inject;
import org.apache.calcite.jdbc.JavaTypeFactoryImpl;
import org.apache.calcite.schema.SchemaPlus;
import org.apache.calcite.schema.Table;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.core.Response;

import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

@Path("/api/connections/{id}")
public class DesignResource {

    public record ColumnInfo(String name, String type) {
    }

    /**
     * The compiled design: canonical SQL (what is saved), the complete SQL for the target database with
     * its values written in and its named parameters kept as {@code :name} (the designer's output), the
     * result columns, and the parameters with their types. On failure, the SQL produced
     * so far and the diagnostics.
     */
    public record Compiled(String canonicalSql, String targetSql, List<ColumnInfo> columns,
                           List<ColumnInfo> parameters, List<DiagnosticJson> diagnostics) {

        Compiled(String canonicalSql, String targetSql, List<ColumnInfo> columns, List<DiagnosticJson> diagnostics) {
            this(canonicalSql, targetSql, columns, List.of(), diagnostics);
        }
    }

    /**
     * A table, or a saved query by ID, to add to the canvas, and the design it is added to (its
     * sources and joins are read).
     */
    public record AddSource(DesignJson design, String table, String query) {
    }

    /**
     * What adding a source added: the source with its alias, the joins drawn automatically, and the
     * source's fields with their types, for its card.
     */
    public record Added(DesignJson.SourceJson source, List<DesignJson.JoinJson> joins, List<ColumnInfo> fields) {
    }

    @Inject
    SavedQueryStore savedQueries;

    @Inject
    SecurityIdentity identity;

    @Inject
    TargetDatabases databases;

    @Inject
    Catalogs catalogs;

    @POST
    @Path("design:compile")
    public Response compile(@PathParam("id") String id, DesignJson design) throws SQLException {
        TargetDatabases.Target target = databases.target(id);
        DesignView view;
        try {
            view = design.toDesignView();
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new BadRequestException("Malformed design: " + e.getMessage());
        }
        try {
            DesignCompiler.Result r = DesignCompiler.compile(view, new QueryCompiler(catalogs.withSavedQueries(target), target.dialect()));
            List<ColumnInfo> columns = r.query().columns().stream()
                    .map(c -> new ColumnInfo(c.name(), c.type().getSqlTypeName().getName())).toList();
            List<ColumnInfo> parameters = r.query().queryParameters().stream()
                    .map(p -> new ColumnInfo(p.name(), p.type().getSqlTypeName().getName())).toList();
            return Response.ok(new Compiled(r.canonicalSql(), r.query().sql(), columns, parameters, List.of())).build();
        } catch (DesignException e) {
            return unprocessable(new Compiled(null, null, List.of(), List.of(diagnostic(design, e))));
        } catch (CompilationException e) {
            return unprocessable(new Compiled(DesignToSql.toSql(view), null, List.of(),
                    e.diagnostics().stream().map(DiagnosticJson::sql).toList()));
        }
    }

    /** The design element at fault, as the designer names it. */
    private static DiagnosticJson diagnostic(DesignJson design, DesignException e) {
        if (e.source() != null) {
            return DiagnosticJson.source(e.source(), e.getMessage());
        }
        if (e.join() != null) {
            return DiagnosticJson.join(e.join(), e.getMessage());
        }
        if (e.column() != null) {
            return DiagnosticJson.grid(design.columnId(e.column()), e.criteriaRow(), e.getMessage());
        }
        return DiagnosticJson.design(e.getMessage());
    }

    @POST
    @Path("design:add-source")
    public Response addSource(@PathParam("id") String id, AddSource request) throws SQLException {
        TargetDatabases.Target target = databases.target(id);
        if (request == null || (request.table() == null) == (request.query() == null)) {
            throw new BadRequestException("Name a table or a saved query to add, not both.");
        }
        DesignView before;
        try {
            before = request.design() == null ? DesignView.of(List.of(), List.of(), List.of())
                    : request.design().toDesignView();
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new BadRequestException("Malformed design: " + e.getMessage());
        }
        SchemaPlus schema = catalogs.withSavedQueries(target);
        DesignView after;
        List<ColumnInfo> fields;
        try {
            after = request.table() != null ? Canvas.addSource(before, schema, request.table())
                    : Canvas.addSavedQuery(before, schema, request.query(), savedQueryName(request.query()));
            fields = fields(schema, after.sources().getLast().table());
        } catch (DesignException e) {
            return unprocessable(new Compiled(null, null, List.of(), List.of(DiagnosticJson.design(e.getMessage()))));
        } catch (RuntimeException e) {
            SavedQuerySchema.InvalidSavedQueryException invalid = SavedQuerySchema.find(e);
            if (invalid == null) {
                throw e;
            }
            return unprocessable(new Compiled(null, null, List.of(), List.of(DiagnosticJson.design(invalid.getMessage()))));
        }
        DesignJson added = DesignJson.from(after);
        return Response.ok(new Added(added.sources().getLast(),
                added.joins().subList(before.joins().size(), after.joins().size()), fields)).build();
    }

    /** The user's saved query's name; a query that is not theirs is reported as missing. */
    private String savedQueryName(String id) throws SQLException {
        try {
            return savedQueries.latest(UUID.fromString(id), identity.getPrincipal().getName())
                    .map(SavedQueriesResource.QueryView::name)
                    .orElseThrow(() -> new DesignException("There is no saved query with ID " + id + ".", null));
        } catch (IllegalArgumentException e) {
            throw new DesignException("There is no saved query with ID " + id + ".", null);
        }
    }

    /** A source's fields and types, as Calcite's validator sees them. */
    private static List<ColumnInfo> fields(SchemaPlus schema, List<String> qualifiedName) {
        SchemaPlus s = schema;
        for (String part : qualifiedName.subList(0, qualifiedName.size() - 1)) {
            s = s.subSchemas().get(part);
        }
        Table table = s.tables().get(qualifiedName.getLast());
        return table.getRowType(new JavaTypeFactoryImpl()).getFieldList().stream()
                .map(f -> new ColumnInfo(f.getName(), f.getType().getSqlTypeName().getName())).toList();
    }

    private static Response unprocessable(Object entity) {
        return Response.status(422).entity(entity).build();
    }
}
