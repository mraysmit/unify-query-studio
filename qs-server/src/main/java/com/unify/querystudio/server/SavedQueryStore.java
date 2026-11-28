package com.unify.querystudio.server;

import com.unify.querystudio.compiler.FieldReference;
import com.unify.querystudio.server.SavedQueriesResource.QueryView;
import com.unify.querystudio.server.SavedQueriesResource.Summary;
import io.agroal.api.AgroalDataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Saved queries and their versions in the application database, in plain SQL (no ORM). Every
 * method checks ownership in its own statements, so a query that is not the caller's is "not found".
 */
@ApplicationScoped
public class SavedQueryStore {

    /** The outcome of saving a new version. */
    public sealed interface Update {
    }

    public record Saved(int version) implements Update {
    }

    /** Someone saved a newer version since {@code baseVersion}. */
    public record Stale(QueryView current) implements Update {
    }

    public record NotFound() implements Update {
    }

    private static final TypeReference<Map<String, Object>> LAYOUT = new TypeReference<>() {
    };

    private static final TypeReference<List<FieldReference>> REFERENCES = new TypeReference<>() {
    };

    private static final String VIEW = """
            SELECT q.id, q.name, q.connection_id, v.version, v.sql, v.layout, v.author, v.saved_at, q.description
            FROM saved_query q JOIN query_version v ON v.query_id = q.id
            """;

    @Inject
    AgroalDataSource db;

    @Inject
    ObjectMapper json;

    /** A new query at version 1. */
    public UUID create(String owner, String name, String description, String connectionId, String sql,
                       Map<String, Object> layout, List<FieldReference> references) throws SQLException {
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        inTransaction(c -> {
            try (PreparedStatement s = c.prepareStatement("""
                    INSERT INTO saved_query (id, owner, name, connection_id, latest_version, created_at, description)
                    VALUES (?, ?, ?, ?, 1, ?, ?)""")) {
                s.setObject(1, id);
                s.setString(2, owner);
                s.setString(3, name);
                s.setString(4, connectionId);
                s.setTimestamp(5, Timestamp.from(now));
                s.setString(6, description);
                s.executeUpdate();
            }
            insertVersion(c, id, 1, sql, layout, references, owner, now);
            return null;
        });
        return id;
    }

    public List<Summary> list(String owner) throws SQLException {
        try (Connection c = db.getConnection();
             PreparedStatement s = c.prepareStatement("""
                     SELECT id, name, description, connection_id, latest_version FROM saved_query
                     WHERE owner = ? ORDER BY name""")) {
            s.setString(1, owner);
            List<Summary> out = new ArrayList<>();
            try (ResultSet r = s.executeQuery()) {
                while (r.next()) {
                    out.add(new Summary(r.getString(1), r.getString(2), r.getString(3), r.getString(4), r.getInt(5)));
                }
            }
            return out;
        }
    }

    /** The latest version, if the owner owns the query. */
    public Optional<QueryView> latest(UUID id, String owner) throws SQLException {
        try (Connection c = db.getConnection()) {
            return latest(c, id, owner);
        }
    }

    /** One version ({@code null} for the latest), if the owner owns the query and the version exists. */
    public Optional<QueryView> version(UUID id, String owner, Integer version) throws SQLException {
        if (version == null) {
            return latest(id, owner);
        }
        try (Connection c = db.getConnection();
             PreparedStatement s = c.prepareStatement(VIEW + "WHERE q.id = ? AND q.owner = ? AND v.version = ?")) {
            s.setObject(1, id);
            s.setString(2, owner);
            s.setInt(3, version);
            return views(s).stream().findFirst();
        }
    }

    /** Every version, newest first; empty if the owner does not own the query. */
    public List<QueryView> versions(UUID id, String owner) throws SQLException {
        try (Connection c = db.getConnection();
             PreparedStatement s = c.prepareStatement(VIEW + "WHERE q.id = ? AND q.owner = ? ORDER BY v.version DESC")) {
            s.setObject(1, id);
            s.setString(2, owner);
            return views(s);
        }
    }

    /** The IDs of the owner's saved queries on one connection. */
    public Set<String> ids(String owner, String connectionId) throws SQLException {
        try (Connection c = db.getConnection();
             PreparedStatement s = c.prepareStatement("SELECT id FROM saved_query WHERE owner = ? AND connection_id = ?")) {
            s.setString(1, owner);
            s.setString(2, connectionId);
            Set<String> ids = new HashSet<>();
            try (ResultSet r = s.executeQuery()) {
                while (r.next()) {
                    ids.add(r.getString(1));
                }
            }
            return ids;
        }
    }

    /** The fields the latest version uses (its catalog fingerprint); empty if none was recorded. */
    public List<FieldReference> latestReferences(UUID id, String owner) throws SQLException {
        try (Connection c = db.getConnection();
             PreparedStatement s = c.prepareStatement("""
                     SELECT v.catalog_refs FROM saved_query q JOIN query_version v ON v.query_id = q.id
                     WHERE q.id = ? AND q.owner = ? AND v.version = q.latest_version""")) {
            s.setObject(1, id);
            s.setString(2, owner);
            try (ResultSet r = s.executeQuery()) {
                String refs = r.next() ? r.getString(1) : null;
                return refs == null ? List.of() : json.readValue(refs, REFERENCES);
            }
        }
    }

    /** The connection the query runs on, if the owner owns it. */
    public Optional<String> connectionOf(UUID id, String owner) throws SQLException {
        try (Connection c = db.getConnection();
             PreparedStatement s = c.prepareStatement("SELECT connection_id FROM saved_query WHERE id = ? AND owner = ?")) {
            s.setObject(1, id);
            s.setString(2, owner);
            try (ResultSet r = s.executeQuery()) {
                return r.next() ? Optional.of(r.getString(1)) : Optional.empty();
            }
        }
    }

    /**
     * A new version, unless someone saved since {@code baseVersion}. The query row is locked
     * ({@code FOR UPDATE}) while the version is checked and written, so two saves cannot both pass.
     */
    public Update update(UUID id, String owner, int baseVersion, String name, String description, String sql,
                         Map<String, Object> layout, List<FieldReference> references) throws SQLException {
        return inTransaction(c -> {
            int latest;
            try (PreparedStatement s = c.prepareStatement(
                    "SELECT latest_version FROM saved_query WHERE id = ? AND owner = ? FOR UPDATE")) {
                s.setObject(1, id);
                s.setString(2, owner);
                try (ResultSet r = s.executeQuery()) {
                    if (!r.next()) {
                        return new NotFound();
                    }
                    latest = r.getInt(1);
                }
            }
            if (latest != baseVersion) {
                return new Stale(latest(c, id, owner).orElseThrow());
            }
            int version = latest + 1;
            try (PreparedStatement s = c.prepareStatement(
                    "UPDATE saved_query SET latest_version = ?, name = ?, description = ? WHERE id = ?")) {
                s.setInt(1, version);
                s.setString(2, name);
                s.setString(3, description);
                s.setObject(4, id);
                s.executeUpdate();
            }
            insertVersion(c, id, version, sql, layout, references, owner, Instant.now());
            return new Saved(version);
        });
    }

    private Optional<QueryView> latest(Connection c, UUID id, String owner) throws SQLException {
        try (PreparedStatement s = c.prepareStatement(
                VIEW + "WHERE q.id = ? AND q.owner = ? AND v.version = q.latest_version")) {
            s.setObject(1, id);
            s.setString(2, owner);
            return views(s).stream().findFirst();
        }
    }

    private void insertVersion(Connection c, UUID queryId, int version, String sql, Map<String, Object> layout,
                               List<FieldReference> references, String author, Instant savedAt) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("""
                INSERT INTO query_version (id, query_id, version, sql, layout, author, saved_at, catalog_refs)
                VALUES (?, ?, ?, ?, CAST(? AS jsonb), ?, ?, CAST(? AS jsonb))""")) {
            s.setObject(1, UUID.randomUUID());
            s.setObject(2, queryId);
            s.setInt(3, version);
            s.setString(4, sql);
            s.setString(5, layout == null ? null : json.writeValueAsString(layout));
            s.setString(6, author);
            s.setTimestamp(7, Timestamp.from(savedAt));
            s.setString(8, json.writeValueAsString(references));
            s.executeUpdate();
        }
    }

    private List<QueryView> views(PreparedStatement s) throws SQLException {
        List<QueryView> out = new ArrayList<>();
        try (ResultSet r = s.executeQuery()) {
            while (r.next()) {
                String layout = r.getString(6);
                out.add(new QueryView(r.getString(1), r.getString(2), r.getString(9), r.getString(3), r.getInt(4), r.getString(5),
                        layout == null ? null : json.readValue(layout, LAYOUT), r.getString(7),
                        r.getTimestamp(8).toInstant(), null));
            }
        }
        return out;
    }

    private interface Work<T> {
        T run(Connection c) throws SQLException;
    }

    /** Runs the work in one database transaction: committed if it returns, rolled back if it throws. */
    private <T> T inTransaction(Work<T> work) throws SQLException {
        try (Connection c = db.getConnection()) {
            c.setAutoCommit(false);
            try {
                T result = work.run(c);
                c.commit();
                return result;
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            }
        }
    }
}
