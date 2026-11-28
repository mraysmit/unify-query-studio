package com.unify.querystudio.server;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.oidc.server.OidcWiremockTestResource;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.UUID;

import static com.unify.querystudio.server.SavedQueriesTest.as;
import static com.unify.querystudio.server.SavedQueriesTest.save;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;

/**
 * Opening a saved query reports how the tables and fields it uses have changed since it was saved,
 * so the designer can mark them; nothing is dropped silently.
 */
@QuarkusTest
@QuarkusTestResource(NorthstarConnection.class)
@QuarkusTestResource(OidcWiremockTestResource.class)
class DriftCheckTest {

    /** Changes the test database directly; the server's own connections are read-only. */
    static void execute(String... statements) throws Exception {
        String url = ConfigProvider.getConfig().getValue("qs.connections.northstar.jdbc-url", String.class);
        try (Connection c = DriverManager.getConnection(url); Statement s = c.createStatement()) {
            for (String sql : statements) {
                s.execute(sql);
            }
        }
    }

    @Test
    void anUnchangedQueryOpensWithoutDrift() {
        String id = save("alice", "Orders", SavedQueriesTest.SQL_1);
        as("alice").get("/api/queries/" + id).then().statusCode(200).body("drift", empty());
    }

    @Test
    void aMissingFieldAndAChangedTypeAreReportedWhenTheQueryIsOpened() throws Exception {
        String table = "Scratch_" + UUID.randomUUID().toString().substring(0, 8);
        execute("CREATE TABLE \"" + table + "\" (a INTEGER, b TEXT)");
        String id = save("alice", "Scratch", "SELECT \"%1$s\".\"a\", \"%1$s\".\"b\" FROM \"%1$s\"".formatted(table));
        execute("DROP TABLE \"" + table + "\"", "CREATE TABLE \"" + table + "\" (a TEXT, c TEXT)");
        as("alice").get("/api/queries/" + id).then().log().ifValidationFails().statusCode(200)
                .body("drift", hasSize(2))
                .body("drift.problem", containsInAnyOrder("TYPE_CHANGED", "FIELD_MISSING"))
                .body("drift.find { it.column == 'a' }.savedType", equalTo("BIGINT"))
                .body("drift.find { it.column == 'a' }.currentType", equalTo("VARCHAR"))
                .body("drift.find { it.column == 'b' }.table[0]", equalTo(table));
        execute("DROP TABLE \"" + table + "\"");
        as("alice").get("/api/queries/" + id).then().statusCode(200)
                .body("drift.problem", containsInAnyOrder("TABLE_MISSING"));
    }
}
