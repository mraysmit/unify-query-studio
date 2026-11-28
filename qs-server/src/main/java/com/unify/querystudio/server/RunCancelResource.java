package com.unify.querystudio.server;

import io.quarkus.security.identity.SecurityIdentity;
import jakarta.inject.Inject;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.core.Response;

/** Cancels a running statement; only the user who started the run may, and others get 404. */
@Path("/api/runs/{runId}")
public class RunCancelResource {

    @Inject
    Runs runs;

    @Inject
    SecurityIdentity identity;

    @DELETE
    public Response cancel(@PathParam("runId") String runId) {
        if (!runs.cancel(runId, identity.getPrincipal().getName())) {
            throw new NotFoundException();
        }
        return Response.noContent().build();
    }
}
