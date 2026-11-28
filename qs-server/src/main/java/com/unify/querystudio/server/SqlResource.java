package com.unify.querystudio.server;

import com.unify.querystudio.compiler.CompilationException;
import com.unify.querystudio.compiler.QueryCompiler;
import com.unify.querystudio.design.SqlToDesign;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.core.Response;

import java.sql.SQLException;
import java.util.List;

/** The SQL view's way back: edited SQL is validated, then shown as a design when the designer can. */
@Path("/api/connections/{id}")
public class SqlResource {

    public record SqlText(String sql) {
    }

    /** A design rebuilt from SQL, or the reason the query can only be shown as SQL. */
    public record Parsed(boolean representable, String reason, DesignJson design, List<DiagnosticJson> diagnostics) {
    }

    @Inject
    TargetDatabases databases;

    @Inject
    Catalogs catalogs;

    @POST
    @Path("sql:parse")
    public Response parse(@PathParam("id") String id, SqlText body) throws SQLException {
        TargetDatabases.Target target = databases.target(id);
        if (body == null || body.sql() == null) {
            throw new BadRequestException("No SQL given.");
        }
        try {
            new QueryCompiler(catalogs.withSavedQueries(target), target.dialect()).compile(body.sql());
        } catch (CompilationException e) {
            return Response.status(422).entity(new Parsed(false, null, null,
                    e.diagnostics().stream().map(DiagnosticJson::sql).toList())).build();
        }
        Parsed parsed = switch (SqlToDesign.toDesign(body.sql())) {
            case SqlToDesign.Representable r -> new Parsed(true, null, DesignJson.from(r.design()), List.of());
            case SqlToDesign.SqlOnly s -> new Parsed(false, s.reason(), null, List.of());
        };
        return Response.ok(parsed).build();
    }
}
