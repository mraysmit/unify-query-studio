package com.unify.querystudio.server;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Server configuration. Connections exist only here: clients name a connection by its ID and never
 * see or send a JDBC URL (technical design, security model).
 */
@ConfigMapping(prefix = "qs")
public interface QueryStudioConfig {

    /** Configured target databases, by ID. */
    Map<String, Connection> connections();

    /** Bounds on every run. */
    Run run();

    interface Connection {
        /** Name shown to users. */
        String name();

        /** A {@code DialectSupport} ID, such as {@code sqlite}. */
        String dialect();

        String jdbcUrl();

        /** Groups that may see this connection, its catalog, and design against it. None configured: nobody. */
        Optional<Set<String>> useRoles();

        /** Groups that may also run queries on it. None configured: nobody. */
        Optional<Set<String>> runRoles();
    }

    interface Run {
        /** The most rows a preview may ask for; larger requests are reduced to this. */
        @WithDefault("1000")
        int maxRows();

        /** How long a statement may run before it is stopped. */
        @WithDefault("PT30S")
        Duration timeout();
    }
}
