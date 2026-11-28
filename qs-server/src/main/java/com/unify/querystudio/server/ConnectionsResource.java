package com.unify.querystudio.server;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;

import java.util.List;

@Path("/api/connections")
public class ConnectionsResource {

    /** What a client may know about a connection: never its JDBC URL or credentials. */
    public record ConnectionInfo(String id, String name, String dialect) {
    }

    @Inject
    QueryStudioConfig config;

    @Inject
    TargetDatabases databases;

    /** The connections the signed-in user may use. */
    @GET
    public List<ConnectionInfo> list() {
        return databases.usable().stream()
                .map(id -> new ConnectionInfo(id, config.connections().get(id).name(), config.connections().get(id).dialect()))
                .toList();
    }
}
