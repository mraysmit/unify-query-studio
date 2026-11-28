package com.unify.querystudio.server;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.oidc.server.OidcWiremockTestResource;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;

/** Connections are granted to groups: "use" to see and design, "run" to execute. */
@QuarkusTest
@QuarkusTestResource(NorthstarConnection.class)
@QuarkusTestResource(OidcWiremockTestResource.class)
class PermissionsTest {

    static RequestSpecification as(String user, String... groups) {
        return given().urlEncodingEnabled(false).auth().oauth2(Users.token(user, groups)).contentType(ContentType.JSON);
    }

    static final String QUERY = "SELECT \"Orders\".\"order_id\" FROM \"Orders\"";

    static Map<String, Object> run() {
        return Map.of("runId", UUID.randomUUID().toString(), "sql", QUERY, "maxRows", 5);
    }

    @Test
    void usersSeeOnlyTheConnectionsTheirGroupsMayUse() {
        as("alice", "user").get("/api/connections").then().statusCode(200).body("id", contains("northstar"));
        as("fiona", "finance").get("/api/connections").then().statusCode(200).body("id", contains("finance"));
        as("nobody", "guest").get("/api/connections").then().statusCode(200).body("$", empty());
    }

    @Test
    void aConnectionYouMayNotUseLooksLikeOneThatDoesNotExist() {
        as("alice", "user").get("/api/connections/finance/catalog").then().statusCode(404);
        as("alice", "user").body(Map.of("sql", QUERY)).post("/api/connections/finance/sql:parse").then().statusCode(404);
        as("alice", "user").body(DesignResourceTest.completedOrders(">= 100", "total"))
                .post("/api/connections/finance/design:compile").then().statusCode(404);
        as("alice", "user").body(run()).post("/api/connections/finance/runs").then().statusCode(404);
    }

    @Test
    void viewersMayDesignButNotRun() {
        as("vic", "viewer").get("/api/connections/northstar/catalog").then().statusCode(200);
        as("vic", "viewer").body(Map.of("sql", QUERY)).post("/api/connections/northstar/sql:parse").then().statusCode(200);
        as("vic", "viewer").body(run()).post("/api/connections/northstar/runs").then().statusCode(403);
        as("alice", "user").body(run()).post("/api/connections/northstar/runs").then().statusCode(200);
    }
}
