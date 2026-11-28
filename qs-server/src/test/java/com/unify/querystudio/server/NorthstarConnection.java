package com.unify.querystudio.server;

import com.unify.querystudio.testkit.NorthstarDatabase;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Configures connections to a fresh copy of the Northstar SQLite database: "northstar" for the user
 * group (viewers may use but not run it) and "finance" for the finance group only.
 */
public class NorthstarConnection implements QuarkusTestResourceLifecycleManager {

    @Override
    public Map<String, String> start() {
        try {
            // Each Quarkus restart (another test profile) has a new class loader, and DriverManager only
            // sees drivers registered from the caller's loader: initialise the driver here.
            Class.forName("org.sqlite.JDBC", true, NorthstarConnection.class.getClassLoader());
            Path db = NorthstarDatabase.create(Files.createTempDirectory("qs-server").resolve("northstar.db"));
            return Map.of(
                    "qs.connections.northstar.name", "Northstar sample",
                    "qs.connections.northstar.dialect", "sqlite",
                    "qs.connections.northstar.jdbc-url", "jdbc:sqlite:" + db,
                    "qs.connections.northstar.use-roles", "user,viewer",
                    "qs.connections.northstar.run-roles", "user",
                    // The same file under another name, for the finance group only.
                    "qs.connections.finance.name", "Finance",
                    "qs.connections.finance.dialect", "sqlite",
                    "qs.connections.finance.jdbc-url", "jdbc:sqlite:" + db,
                    "qs.connections.finance.use-roles", "finance",
                    "qs.connections.finance.run-roles", "finance");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void stop() {
    }
}
