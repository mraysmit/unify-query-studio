package com.unify.querystudio.server;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.oidc.server.OidcWiremockTestResource;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Saved queries: canonical SQL plus layout, a new version on every save, optimistic locking, and
 * private to their owner until sharing exists.
 */
@QuarkusTest
@QuarkusTestResource(NorthstarConnection.class)
@QuarkusTestResource(OidcWiremockTestResource.class)
class SavedQueriesTest {

    static RequestSpecification as(String user, String... groups) {
        return given().auth().oauth2(Users.token(user, groups.length == 0 ? new String[]{"user"} : groups))
                .contentType(ContentType.JSON);
    }

    static final String SQL_1 = "SELECT \"Orders\".\"order_id\" FROM \"Orders\"";
    static final String SQL_2 = "SELECT \"Orders\".\"order_id\", \"Orders\".\"total\" FROM \"Orders\"";

    static Map<String, Object> query(String name, String sql, Integer baseVersion) {
        Map<String, Object> body = new HashMap<>();
        body.put("name", name);
        body.put("connectionId", "northstar");
        body.put("sql", sql);
        body.put("layout", Map.of("tables", Map.of("Orders", Map.of("x", 40, "y", 60))));
        if (baseVersion != null) body.put("baseVersion", baseVersion);
        return body;
    }

    static String save(String user, String name, String sql) {
        return as(user).body(query(name, sql, null)).post("/api/queries").then()
                .statusCode(201).body("version", equalTo(1)).extract().path("id");
    }

    @Test
    void aSavedQueryKeepsItsSqlLayoutAuthorAndVersion() {
        String id = save("alice", "Order IDs", SQL_1);
        as("alice").get("/api/queries/" + id).then().statusCode(200)
                .body("name", equalTo("Order IDs"))
                .body("connectionId", equalTo("northstar"))
                .body("sql", equalTo(SQL_1))
                .body("layout.tables.Orders.x", equalTo(40))
                .body("version", equalTo(1))
                .body("author", equalTo("alice"))
                .body("savedAt", notNullValue());
        as("alice").get("/api/queries").then().statusCode(200).body("id", hasItem(id));
    }

    @Test
    void everySaveIsANewVersion() {
        String id = save("alice", "Totals", SQL_1);
        as("alice").body(query("Totals", SQL_2, 1)).put("/api/queries/" + id).then()
                .statusCode(200).body("version", equalTo(2));
        as("alice").get("/api/queries/" + id).then().body("sql", equalTo(SQL_2)).body("version", equalTo(2));
        as("alice").get("/api/queries/" + id + "/versions").then().statusCode(200)
                .body("version", contains(2, 1))
                .body("sql", contains(SQL_2, SQL_1));
    }

    @Test
    void savingOverANewerVersionIsRefused_andTheNewerVersionIsOffered() {
        String id = save("alice", "Shared edit", SQL_1);
        as("alice").body(query("Shared edit", SQL_2, 1)).put("/api/queries/" + id).then().statusCode(200);
        as("alice").body(query("Shared edit", SQL_1, 1)).put("/api/queries/" + id).then()
                .statusCode(409)
                .body("current.version", equalTo(2))
                .body("current.sql", equalTo(SQL_2));
    }

    @Test
    void queriesArePrivateToTheirOwner() {
        String id = save("alice", "Private", SQL_1);
        as("bob").get("/api/queries/" + id).then().statusCode(404);
        as("bob").body(query("Mine now", SQL_2, 1)).put("/api/queries/" + id).then().statusCode(404);
        as("bob").get("/api/queries").then().statusCode(200).body("id", not(hasItem(id)));
    }

    @Test
    void onlyValidSqlForAConnectionYouMayUseIsSaved() {
        as("alice").body(query("Broken", "SELECT \"nope\" FROM \"Orders\"", null)).post("/api/queries").then()
                .statusCode(422).body("diagnostics[0].message", containsString("nope"));
        Map<String, Object> elsewhere = query("Elsewhere", SQL_1, null);
        elsewhere.put("connectionId", "finance");
        as("alice").body(elsewhere).post("/api/queries").then().statusCode(404);
    }

    @Test
    void aQueryKeepsItsDescription() {
        Map<String, Object> body = query("Described", SQL_1, null);
        body.put("description", "Every order, for the monthly check");
        String id = as("alice").body(body).post("/api/queries").then().statusCode(201).extract().path("id");
        as("alice").get("/api/queries/" + id).then().body("description", equalTo("Every order, for the monthly check"));
        as("alice").get("/api/queries").then().body("find { it.id == '" + id + "' }.description",
                equalTo("Every order, for the monthly check"));
        Map<String, Object> edit = query("Described", SQL_1, 1);
        edit.put("description", "Changed");
        as("alice").body(edit).put("/api/queries/" + id).then().statusCode(200);
        as("alice").get("/api/queries/" + id).then().body("description", equalTo("Changed"));
    }
}
