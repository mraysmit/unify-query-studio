package com.unify.querystudio.server;

import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.server.ServerExceptionMapper;

import java.sql.SQLException;
import java.util.List;

/**
 * A target database that cannot be opened or read is a bad gateway. The client gets a fixed
 * message: driver messages can carry file paths and host names, which stay in the server log.
 */
public class DatabaseErrors {

    private static final Logger LOG = Logger.getLogger(DatabaseErrors.class);

    @ServerExceptionMapper
    public Response unreachable(SQLException e) {
        LOG.warn("Target database could not be reached", e);
        return Response.status(502).entity(new RunsResource.Failure("The database could not be reached.", List.of())).build();
    }
}
