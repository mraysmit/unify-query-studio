package com.unify.querystudio.testkit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * The prototype's Northstar Commerce sample data as a SQLite file, built from
 * {@code testkit/northstar.json}. Dates are stored as ISO-8601 text and booleans
 * as 0/1, as SQLite applications normally store them.
 */
public final class NorthstarDatabase {

    private static final String RESOURCE = "/testkit/northstar.json";

    private NorthstarDatabase() {
    }

    /** Creates the database at {@code file}, replacing any file already there. */
    public static Path create(Path file) {
        try {
            Files.deleteIfExists(file);
            Files.createDirectories(file.toAbsolutePath().getParent());
            JsonNode data = read();
            try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath())) {
                c.setAutoCommit(false);
                for (JsonNode table : data.get("tables")) {
                    createTable(c, table, data.get("relationships"));
                    insertRows(c, table);
                }
                c.commit();
            }
            return file;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (SQLException e) {
            throw new IllegalStateException("Could not create the Northstar database at " + file, e);
        }
    }

    /** Creates the database only if {@code file} does not exist. */
    public static Path createIfMissing(Path file) {
        return Files.exists(file) ? file : create(file);
    }

    private static JsonNode read() throws IOException {
        try (InputStream in = NorthstarDatabase.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("Missing resource " + RESOURCE);
            }
            return new ObjectMapper().readTree(in);
        }
    }

    private static void createTable(Connection c, JsonNode table, JsonNode relationships) throws SQLException {
        String name = table.get("name").asText();
        List<String> columns = new ArrayList<>();
        for (JsonNode f : table.get("fields")) {
            String type = switch (f.get("type").asText()) {
                case "number" -> "INTEGER";
                case "date" -> "DATE";
                case "boolean" -> "BOOLEAN";
                default -> "TEXT";
            };
            columns.add(quote(f.get("name").asText()) + " " + type + (f.path("primaryKey").asBoolean() ? " PRIMARY KEY" : ""));
        }
        for (JsonNode r : relationships) {
            if (r.get("toTable").asText().equals(name)) {
                columns.add("FOREIGN KEY (" + quote(r.get("toField").asText()) + ") REFERENCES "
                        + quote(r.get("fromTable").asText()) + " (" + quote(r.get("fromField").asText()) + ")");
            }
        }
        c.createStatement().execute("CREATE TABLE " + quote(name) + " (" + String.join(", ", columns) + ")");
    }

    private static void insertRows(Connection c, JsonNode table) throws SQLException {
        List<String> fields = new ArrayList<>();
        table.get("fields").forEach(f -> fields.add(f.get("name").asText()));
        String sql = "INSERT INTO " + quote(table.get("name").asText()) + " VALUES (" + "?, ".repeat(fields.size() - 1) + "?)";
        try (PreparedStatement insert = c.prepareStatement(sql)) {
            for (JsonNode row : table.get("rows")) {
                for (int i = 0; i < fields.size(); i++) {
                    insert.setObject(i + 1, value(row.get(fields.get(i))));
                }
                insert.addBatch();
            }
            insert.executeBatch();
        }
    }

    private static Object value(JsonNode v) {
        if (v == null || v.isNull()) return null;
        if (v.isBoolean()) return v.asBoolean() ? 1 : 0;
        if (v.isIntegralNumber()) return v.asLong();
        if (v.isNumber()) return v.asDouble();
        return v.asText();
    }

    private static String quote(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }
}
