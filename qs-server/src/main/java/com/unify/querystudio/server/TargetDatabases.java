package com.unify.querystudio.server;

import com.unify.querystudio.dialects.DialectSupport;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.NotFoundException;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/** The configured target databases: a connection ID resolves to its dialect and a JDBC connection. */
@ApplicationScoped
public class TargetDatabases {

    /** One configured connection with its dialect bundle. */
    public record Target(String id, QueryStudioConfig.Connection config, DialectSupport dialect) {
    }

    @Inject
    QueryStudioConfig config;

    @Inject
    SecurityIdentity identity;

    /**
     * The connection with this ID, if the signed-in user may use it. A connection they may not use
     * answers 404 exactly like one that does not exist, so its existence is not revealed.
     */
    public Target target(String id) {
        QueryStudioConfig.Connection c = config.connections().get(id);
        if (c == null || !mayUse(c)) {
            throw new NotFoundException();
        }
        return new Target(id, c, DialectSupport.forId(c.dialect()));
    }

    /** The connection, if the user may also run queries on it; 403 if they may only use it. */
    public Target runnable(String id) {
        Target target = target(id);
        if (!inAny(target.config().runRoles())) {
            throw new ForbiddenException("You may design queries on this connection but not run them.");
        }
        return target;
    }

    /** IDs of the connections the signed-in user may use. */
    public Set<String> usable() {
        Set<String> ids = new TreeSet<>();
        config.connections().forEach((id, c) -> {
            if (mayUse(c)) ids.add(id);
        });
        return ids;
    }

    private boolean mayUse(QueryStudioConfig.Connection c) {
        return inAny(c.useRoles()) || inAny(c.runRoles());
    }

    private boolean inAny(Optional<Set<String>> roles) {
        return roles.orElse(Set.of()).stream().anyMatch(identity::hasRole);
    }

    /** Always read-only, through the dialect (for SQLite: no writes, and no file is ever created). */
    public Connection open(Target target) throws SQLException {
        return target.dialect().openReadOnly(target.config().jdbcUrl());
    }
}
