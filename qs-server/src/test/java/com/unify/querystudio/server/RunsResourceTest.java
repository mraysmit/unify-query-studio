package com.unify.querystudio.server;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.oidc.server.OidcWiremockTestResource;
import io.restassured.http.ContentType;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.isOneOf;

@QuarkusTest
@QuarkusTestResource(NorthstarConnection.class)
@QuarkusTestResource(OidcWiremockTestResource.class)
class RunsResourceTest {

    static RequestSpecification as(String user) {
        return given().auth().oauth2(Users.token(user)).contentType(ContentType.JSON);
    }

    static ValidatableResponse run(String user, String runId, String sql, Integer maxRows) {
        Map<String, Object> body = new HashMap<>();
        body.put("runId", runId);
        body.put("sql", sql);
        body.put("maxRows", maxRows);
        return as(user).body(body).when().post("/api/connections/northstar/runs").then().log().ifValidationFails();
    }

    static ValidatableResponse run(String sql, Integer maxRows) {
        return run("alice", UUID.randomUUID().toString(), sql, maxRows);
    }

    static int count(String sql) throws Exception {
        String url = ConfigProvider.getConfig().getValue("qs.connections.northstar.jdbc-url", String.class);
        try (Connection c = DriverManager.getConnection(url); ResultSet rs = c.createStatement().executeQuery(sql)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    @Test
    void aRunReturnsTypedColumnsAndRows() throws Exception {
        int expected = count("SELECT COUNT(*) FROM Orders WHERE total >= 100 AND status = 'Completed'");
        run("""
                SELECT "Orders"."order_id", "Orders"."order_date", "Orders"."total" FROM "Orders"
                WHERE "Orders"."total" >= 100 AND "Orders"."status" = 'Completed'
                ORDER BY "Orders"."order_id\"""", 100)
                .statusCode(200)
                .body("columns.name", contains("order_id", "order_date", "total"))
                .body("columns.type", contains("BIGINT", "DATE", "BIGINT"))
                .body("rows", hasSize(expected))
                .body("rows[0][1]", equalTo("2026-10-03"))
                .body("truncated", equalTo(false));
    }

    @Test
    void booleansStoredAsNumbersComeBackAsBooleans() {
        run("SELECT \"Products\".\"in_stock\" FROM \"Products\"", 100)
                .statusCode(200)
                .body("rows.flatten()", everyItem(isOneOf(true, false)));
    }

    @Test
    void aPreviewStopsAtItsRowLimitAndSaysSo() {
        run("SELECT \"Orders\".\"order_id\" FROM \"Orders\"", 3)
                .statusCode(200)
                .body("rows", hasSize(3))
                .body("truncated", equalTo(true));
    }

    @Test
    void topValuesReturnExactlyNRows() {
        String top3 = """
                SELECT "Customers"."country", COUNT("Orders"."order_id") AS "orders"
                FROM "Customers" INNER JOIN "Orders" ON "Customers"."customer_id" = "Orders"."customer_id"
                GROUP BY "Customers"."country" ORDER BY COUNT("Orders"."order_id") DESC
                FETCH NEXT 3 ROWS ONLY""";
        run(top3, 100).statusCode(200).body("rows", hasSize(3));
    }

    @Test
    void onlyQueriesRun() {
        run("DELETE FROM \"Orders\"", 10)
                .statusCode(422)
                .body("diagnostics[0].message", containsString("Only SELECT"));
    }

    static final String SLOW = "SELECT COUNT(*) FROM \"Orders\" AS \"a\" CROSS JOIN \"Orders\" AS \"b\" CROSS JOIN \"Orders\" AS \"c\" "
            + "CROSS JOIN \"Orders\" AS \"d\" CROSS JOIN \"Orders\" AS \"e\" CROSS JOIN \"Orders\" AS \"f\" CROSS JOIN \"Orders\" AS \"g\"";

    @Test
    void aRunningQueryCanBeCancelledByTheUserWhoStartedIt() throws Exception {
        String runId = UUID.randomUUID().toString();
        CompletableFuture<Integer> status = CompletableFuture.supplyAsync(
                () -> run("alice", runId, SLOW, 10).extract().statusCode());
        // Someone else cannot cancel it, or even learn that it exists.
        awaitCancel("bob", runId, 404);
        awaitCancel("alice", runId, 204);
        assertThat(status.get()).isEqualTo(409);
    }

    @Test
    void cancellingAnUnknownRunIsNotFound() {
        as("alice").when().delete("/api/runs/" + UUID.randomUUID()).then().statusCode(404);
    }

    @Test
    void aRunIdMustBeAUuid() {
        run("alice", "not-a-uuid", "SELECT \"Orders\".\"order_id\" FROM \"Orders\"", 10).statusCode(400);
    }

    /** Repeats the cancel until the run has registered (it starts asynchronously). */
    static void awaitCancel(String user, String runId, int expected) throws InterruptedException {
        int status = 0;
        for (int i = 0; i < 50; i++) {
            status = as(user).when().delete("/api/runs/" + runId).then().extract().statusCode();
            if (status == expected) {
                return;
            }
            Thread.sleep(100);
        }
        assertThat(status).isEqualTo(expected);
    }

    static final String BY_MIN_TOTAL = "SELECT \"Orders\".\"order_id\" FROM \"Orders\" WHERE \"Orders\".\"total\" >= :MinTotal";

    static ValidatableResponse runWith(Map<String, Object> values) {
        Map<String, Object> body = new HashMap<>();
        body.put("runId", UUID.randomUUID().toString());
        body.put("sql", BY_MIN_TOTAL);
        body.put("maxRows", 100);
        body.put("parameterValues", values);
        return as("alice").body(body).when().post("/api/connections/northstar/runs").then().log().ifValidationFails();
    }

    @Test
    void aQueryWithParametersRunsWithTheirValues() throws Exception {
        runWith(Map.of("MinTotal", 300)).statusCode(200)
                .body("rows", hasSize(count("SELECT COUNT(*) FROM Orders WHERE total >= 300")));
    }

    @Test
    void aMissingOrWrongParameterValueIsRefusedWithItsName() {
        runWith(Map.of()).statusCode(422).body("message", containsString(":MinTotal"));
        runWith(Map.of("MinTotal", "lots")).statusCode(422).body("message", containsString("number"));
    }
}
