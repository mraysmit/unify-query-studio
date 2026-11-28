package com.unify.querystudio.server;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.oidc.server.OidcWiremockTestResource;
import org.junit.jupiter.api.Test;

import static com.unify.querystudio.server.SavedQueriesTest.as;
import static com.unify.querystudio.server.SavedQueriesTest.save;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;

/** A saved query is a source on the canvas, as in Access: added by its ID, shown under its name. */
@QuarkusTest
@QuarkusTestResource(NorthstarConnection.class)
@QuarkusTestResource(OidcWiremockTestResource.class)
class SavedQuerySourcesTest {

    static final String BIG_ORDERS =
            "SELECT \"Orders\".\"customer_id\", \"Orders\".\"total\" FROM \"Orders\" WHERE \"Orders\".\"total\" >= 100";

    static String addQuery(String user, String queryId, int status) {
        return as(user).urlEncodingEnabled(false).body("""
                        {"design": {"sources": [{"alias": "Customers", "table": ["Customers"]}]}, "query": "%s"}""".formatted(queryId))
                .post("/api/connections/northstar/design:add-source").then().log().ifValidationFails()
                .statusCode(status).extract().asString();
    }

    static String designOn(String queryId) {
        return """
                {
                  "sources": [{"alias": "Customers", "table": ["Customers"]}, {"alias": "Big orders", "table": ["queries", "%s"]}],
                  "joins": [{"type": "INNER", "left": {"source": "Customers", "column": "customer_id"},
                             "right": {"source": "Big orders", "column": "customer_id"}}],
                  "columns": [{"id": "c1", "table": "Customers", "field": "name"}, {"id": "c2", "table": "Big orders", "field": "total"}]
                }""".formatted(queryId);
    }

    @Test
    void aSavedQueryIsAddedUnderItsName_withItsFields() {
        String id = save("alice", "Big orders", BIG_ORDERS);
        as("alice").urlEncodingEnabled(false).body("""
                        {"design": {"sources": [{"alias": "Customers", "table": ["Customers"]}]}, "query": "%s"}""".formatted(id))
                .post("/api/connections/northstar/design:add-source").then().log().ifValidationFails()
                .statusCode(200)
                .body("source.alias", equalTo("Big orders"))
                .body("source.table", contains("queries", id))
                .body("joins", empty())
                .body("fields.name", contains("customer_id", "total"));
    }

    @Test
    void aDesignOnASavedQueryCompilesToCompleteSql() {
        String id = save("alice", "Big orders", BIG_ORDERS);
        as("alice").urlEncodingEnabled(false).body(designOn(id)).post("/api/connections/northstar/design:compile").then()
                .log().ifValidationFails()
                .statusCode(200)
                .body("canonicalSql", containsString("\"queries\".\"" + id + "\" AS \"Big orders\""))
                .body("targetSql", not(containsString("queries")))
                .body("targetSql", containsString(">= 100"));
    }

    @Test
    void aQuerySavedOnAnotherSavedQueryRuns() {
        String id = save("alice", "Big orders", BIG_ORDERS);
        String onTop = save("alice", "Big order names", """
                SELECT "Customers"."name" FROM "Customers" INNER JOIN "queries"."%s" AS "Big orders"
                ON "Customers"."customer_id" = "Big orders"."customer_id\"""".formatted(id));
        SavedQueryRunsTest.runSaved("alice", onTop, null).statusCode(200).body("columns.name", contains("name"));
    }

    @Test
    void someoneElsesSavedQueryCannotBeUsed() {
        String id = save("alice", "Big orders", BIG_ORDERS);
        addQuery("bob", id, 422);
        as("bob").urlEncodingEnabled(false).body(designOn(id)).post("/api/connections/northstar/design:compile").then()
                .statusCode(422);
    }

    @Test
    void theTableListDoesNotIncludeSavedQueries() {
        save("alice", "Big orders", BIG_ORDERS);
        as("alice").get("/api/connections/northstar/catalog").then().statusCode(200)
                .body("tables.name", not(hasItem("queries")));
    }
}
