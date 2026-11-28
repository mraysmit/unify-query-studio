package com.unify.querystudio.server;

import io.agroal.api.AgroalDataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Writes audit events (technical design, security model): user, connection, the saved query and
 * version if one ran, a SHA-256 of the canonical SQL (not the SQL itself, so criteria values are not
 * kept), the target SQL that ran, row count, duration and outcome. Each event is its own
 * auto-committed statement, so a failed run is still recorded.
 */
@ApplicationScoped
public class AuditLog {

    /**
     * What is known about a run attempt before it starts. Any part may be null when the request did
     * not supply it; {@code queryId} and {@code queryVersion} only when a saved query was run.
     */
    public record Attempt(String user, String connectionId, String runId, String canonicalSql, UUID queryId,
                          Integer queryVersion) {

        public Attempt withSavedQuery(String sql, UUID id, int version) {
            return new Attempt(user, connectionId, runId, sql, id, version);
        }
    }

    @Inject
    AgroalDataSource db;

    /**
     * How a run attempt ended: OK, INVALID, DENIED, CANCELLED, TIMEOUT, FAILED or UNREACHABLE.
     * Target SQL, rows and duration only when known.
     */
    public void run(Attempt attempt, String targetSql, Integer rowCount, Duration duration, String outcome,
                    String message) {
        try (Connection c = db.getConnection();
             PreparedStatement s = c.prepareStatement("""
                     INSERT INTO audit_event (id, at, user_name, action, connection_id, run_id, query_id,
                                              query_version, sql_sha256, target_sql, row_count, duration_ms,
                                              outcome, message)
                     VALUES (?, ?, ?, 'RUN', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""")) {
            s.setObject(1, UUID.randomUUID());
            s.setTimestamp(2, Timestamp.from(Instant.now()));
            s.setString(3, attempt.user());
            s.setString(4, attempt.connectionId());
            s.setString(5, attempt.runId());
            s.setObject(6, attempt.queryId(), Types.OTHER);
            s.setObject(7, attempt.queryVersion(), Types.INTEGER);
            s.setString(8, attempt.canonicalSql() == null ? null : sha256(attempt.canonicalSql()));
            s.setString(9, targetSql);
            s.setObject(10, rowCount, Types.INTEGER);
            s.setObject(11, duration == null ? null : duration.toMillis(), Types.BIGINT);
            s.setString(12, outcome);
            s.setString(13, message);
            s.executeUpdate();
        } catch (SQLException e) {
            // An audit log that silently loses events is worse than a failed request.
            throw new IllegalStateException("The audit event could not be written", e);
        }
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every Java platform", e);
        }
    }
}
