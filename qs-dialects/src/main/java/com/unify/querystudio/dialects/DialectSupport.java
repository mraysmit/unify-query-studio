package com.unify.querystudio.dialects;

import org.apache.calcite.config.NullCollation;
import org.apache.calcite.sql.SqlDialect;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Map;
import java.util.Properties;
import java.util.function.Predicate;

/**
 * Everything database-specific in one place. Supporting another database means adding a bundle
 * here; the catalog, design mapper and compiler do not change.
 *
 * @param id          stable identifier used in connection configuration, e.g. {@code sqlite}
 * @param sqlDialect  the Calcite dialect target SQL is written in
 * @param columnTypes JDBC column metadata to Calcite types
 * @param binder      parameter values to the driver
 * @param systemTable tables the catalog never shows
 * @param readOnly    driver properties that open a connection read-only
 */
public record DialectSupport(
        String id,
        String displayName,
        SqlDialect sqlDialect,
        ColumnTypeResolver columnTypes,
        ParameterBinder binder,
        Predicate<String> systemTable,
        Properties readOnly) {

    private static final Map<String, DialectSupport> SUPPORTED = Map.of(
            "sqlite", new DialectSupport("sqlite", "SQLite", QueryStudioSqliteDialect.INSTANCE,
                    new SqliteColumnTypeResolver(), new SqliteParameterBinder(), name -> name.startsWith("sqlite_"),
                    // SQLITE_OPEN_READONLY (0x01) without SQLITE_OPEN_CREATE: no writes, and a missing file is an error.
                    properties("open_mode", "1")));

    public static DialectSupport forId(String id) {
        DialectSupport support = SUPPORTED.get(id);
        if (support == null) {
            throw new IllegalArgumentException("Unsupported database: " + id);
        }
        return support;
    }

    /** Where the database sorts NULLs; the compiler makes Calcite follow it. */
    public NullCollation nullCollation() {
        return sqlDialect.getNullCollation();
    }

    /**
     * Opens a target database read-only. Defence in depth: the compiler only accepts SELECT, and the
     * database account should have SELECT-only grants as well.
     */
    public Connection openReadOnly(String jdbcUrl) throws SQLException {
        return DriverManager.getConnection(jdbcUrl, readOnly);
    }

    private static Properties properties(String key, String value) {
        Properties p = new Properties();
        p.setProperty(key, value);
        return p;
    }

    public boolean isSystemTable(String name) {
        return systemTable.test(name);
    }
}
