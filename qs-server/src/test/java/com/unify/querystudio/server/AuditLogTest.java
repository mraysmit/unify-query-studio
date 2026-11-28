package com.unify.querystudio.server;

import io.agroal.api.AgroalDataSource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.oidc.server.OidcWiremockTestResource;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Every run attempt is audited: who, where, which SQL, what ran, how many rows, how long, and how it ended. */
@QuarkusTest
@QuarkusTestResource(NorthstarConnection.class)
@QuarkusTestResource(OidcWiremockTestResource.class)
class AuditLogTest {

    @Inject
    AgroalDataSource db;

    static final String SQL = "SELECT \"Orders\".\"order_id\" FROM \"Orders\"";

    /** One row of the audit log, as stored. */
    record AuditEvent(String userName, String connectionId, String sqlSha256, String targetSql, Integer rowCount,
                      Long durationMs, String outcome, Instant at) {
    }

    AuditEvent event(String runId) throws SQLException {
        try (Connection c = db.getConnection();
             PreparedStatement s = c.prepareStatement("""
                     SELECT user_name, connection_id, sql_sha256, target_sql, row_count, duration_ms, outcome, at
                     FROM audit_event WHERE run_id = ?""")) {
            s.setString(1, runId);
            List<AuditEvent> events = new ArrayList<>();
            try (ResultSet r = s.executeQuery()) {
                while (r.next()) {
                    events.add(new AuditEvent(r.getString(1), r.getString(2), r.getString(3), r.getString(4),
                            r.getObject(5, Integer.class), r.getObject(6, Long.class), r.getString(7),
                            r.getTimestamp(8).toInstant()));
                }
            }
            assertThat(events).hasSize(1);
            return events.getFirst();
        }
    }

    static String sha256(String text) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void aRunIsAuditedWithItsSqlRowsAndDuration() throws Exception {
        String runId = UUID.randomUUID().toString();
        RunsResourceTest.run("alice", runId, SQL, 5).statusCode(200);
        AuditEvent e = event(runId);
        assertThat(e.userName()).isEqualTo("alice");
        assertThat(e.connectionId()).isEqualTo("northstar");
        assertThat(e.sqlSha256()).isEqualTo(sha256(SQL));
        assertThat(e.targetSql()).startsWith("SELECT \"order_id\"");
        assertThat(e.rowCount()).isEqualTo(5);
        assertThat(e.durationMs()).isNotNull().isGreaterThanOrEqualTo(0L);
        assertThat(e.outcome()).isEqualTo("OK");
        assertThat(e.at()).isNotNull();
    }

    @Test
    void refusedAndInvalidRunsAreAuditedToo() throws Exception {
        String invalid = UUID.randomUUID().toString();
        RunsResourceTest.run("alice", invalid, "DELETE FROM \"Orders\"", 5).statusCode(422);
        assertThat(event(invalid).outcome()).isEqualTo("INVALID");
        assertThat(event(invalid).targetSql()).isNull();

        String denied = UUID.randomUUID().toString();
        PermissionsTest.as("vic", "viewer").body(Map.of("runId", denied, "sql", SQL, "maxRows", 5))
                .post("/api/connections/northstar/runs").then().statusCode(403);
        assertThat(event(denied).outcome()).isEqualTo("DENIED");
        assertThat(event(denied).userName()).isEqualTo("vic");
    }

    @Test
    void aCancelledRunIsAuditedAsCancelled() throws Exception {
        String runId = UUID.randomUUID().toString();
        var status = java.util.concurrent.CompletableFuture.supplyAsync(
                () -> RunsResourceTest.run("alice", runId, RunsResourceTest.SLOW, 10).extract().statusCode());
        RunsResourceTest.awaitCancel("alice", runId, 204);
        assertThat(status.get()).isEqualTo(409);
        assertThat(event(runId).outcome()).isEqualTo("CANCELLED");
    }
}
