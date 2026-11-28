package com.unify.querystudio.server;

import io.agroal.api.AgroalDataSource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.oidc.server.OidcWiremockTestResource;
import io.restassured.response.ValidatableResponse;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static com.unify.querystudio.server.SavedQueriesTest.SQL_1;
import static com.unify.querystudio.server.SavedQueriesTest.SQL_2;
import static com.unify.querystudio.server.SavedQueriesTest.as;
import static com.unify.querystudio.server.SavedQueriesTest.query;
import static com.unify.querystudio.server.SavedQueriesTest.save;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;

/**
 * A saved query runs by its ID: the latest version unless an earlier one is named. The saved SQL is
 * compiled again on every run, like any other run.
 */
@QuarkusTest
@QuarkusTestResource(NorthstarConnection.class)
@QuarkusTestResource(OidcWiremockTestResource.class)
class SavedQueryRunsTest {

    @Inject
    AgroalDataSource db;

    static ValidatableResponse runSaved(String user, String connection, String runId, String queryId, Integer version,
                                        String... groups) {
        Map<String, Object> body = new HashMap<>();
        body.put("runId", runId);
        body.put("queryId", queryId);
        body.put("version", version);
        body.put("maxRows", 5);
        return as(user, groups).body(body).post("/api/connections/" + connection + "/runs").then()
                .log().ifValidationFails();
    }

    static ValidatableResponse runSaved(String user, String queryId, Integer version) {
        return runSaved(user, "northstar", UUID.randomUUID().toString(), queryId, version);
    }

    /** A query saved as SQL_1 and then saved again as SQL_2 (version 2). */
    static String twoVersions(String user) {
        String id = save(user, "Orders", SQL_1);
        as(user).body(query("Orders", SQL_2, 1)).put("/api/queries/" + id).then().statusCode(200);
        return id;
    }

    @Test
    void aSavedQueryRunsAtItsLatestVersion() {
        String id = twoVersions("alice");
        runSaved("alice", id, null).statusCode(200).body("columns.name", contains("order_id", "total"));
    }

    @Test
    void anEarlierVersionRunsWhenNamed() {
        String id = twoVersions("alice");
        runSaved("alice", id, 1).statusCode(200).body("columns.name", contains("order_id"));
    }

    @Test
    void aQueryYouCannotSeeOrAVersionThatDoesNotExistIsNotFound() {
        String id = twoVersions("alice");
        runSaved("bob", id, null).statusCode(404);
        runSaved("alice", id, 3).statusCode(404);
        runSaved("alice", UUID.randomUUID().toString(), null).statusCode(404);
        runSaved("alice", "not-a-uuid", null).statusCode(404);
    }

    @Test
    void aSavedQueryRunsOnlyOnItsOwnConnection() {
        // Carol may run both connections, but her query was saved for northstar.
        String id = save("carol", "Orders", SQL_1);
        runSaved("carol", "finance", UUID.randomUUID().toString(), id, null, "user", "finance").statusCode(404);
        runSaved("carol", "northstar", UUID.randomUUID().toString(), id, null, "user", "finance").statusCode(200);
    }

    @Test
    void aRunNamesEitherSqlOrASavedQuery_notBoth() {
        String id = save("alice", "Orders", SQL_1);
        Map<String, Object> both = Map.of("runId", UUID.randomUUID().toString(), "sql", SQL_1, "queryId", id);
        as("alice").body(both).post("/api/connections/northstar/runs").then().statusCode(400);
    }

    @Test
    void theAuditLogRecordsWhichSavedQueryAndVersionRan() throws Exception {
        String id = twoVersions("alice");
        String runId = UUID.randomUUID().toString();
        runSaved("alice", "northstar", runId, id, 1).statusCode(200);
        try (Connection c = db.getConnection();
             PreparedStatement s = c.prepareStatement(
                     "SELECT query_id, query_version, sql_sha256 FROM audit_event WHERE run_id = ?")) {
            s.setString(1, runId);
            try (ResultSet r = s.executeQuery()) {
                assertThat(r.next()).isTrue();
                assertThat(r.getString(1)).isEqualTo(id);
                assertThat(r.getInt(2)).isEqualTo(1);
                assertThat(r.getString(3)).isEqualTo(AuditLogTest.sha256(SQL_1));
            }
        }
    }
}
