package com.unify.querystudio.dialects;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Target databases are opened read-only: defence in depth behind the SELECT-only compiler. */
class ReadOnlyConnectionTest {

    static final DialectSupport SQLITE = DialectSupport.forId("sqlite");

    @Test
    void sqliteConnectionsCanReadButNotWrite(@TempDir Path dir) throws Exception {
        Path db = dir.resolve("t.db");
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db)) {
            c.createStatement().execute("CREATE TABLE t (x INTEGER)");
            c.createStatement().execute("INSERT INTO t VALUES (1)");
        }
        try (Connection c = SQLITE.openReadOnly("jdbc:sqlite:" + db)) {
            try (ResultSet rs = c.createStatement().executeQuery("SELECT x FROM t")) {
                assertThat(rs.next()).isTrue();
            }
            assertThatThrownBy(() -> c.createStatement().execute("INSERT INTO t VALUES (2)"))
                    .isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> c.createStatement().execute("CREATE TABLE u (y INTEGER)"))
                    .isInstanceOf(SQLException.class);
        }
    }

    @Test
    void aMissingSqliteFileIsAnErrorAndIsNotCreated(@TempDir Path dir) {
        Path missing = dir.resolve("missing.db");
        assertThatThrownBy(() -> SQLITE.openReadOnly("jdbc:sqlite:" + missing)).isInstanceOf(SQLException.class);
        assertThat(Files.exists(missing)).isFalse();
    }
}
