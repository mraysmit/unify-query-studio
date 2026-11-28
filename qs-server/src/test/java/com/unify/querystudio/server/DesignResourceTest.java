package com.unify.querystudio.server;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.oidc.server.OidcWiremockTestResource;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;

@QuarkusTest
@QuarkusTestResource(NorthstarConnection.class)
@QuarkusTestResource(OidcWiremockTestResource.class)
class DesignResourceTest {

    static RequestSpecification alice() {
        return given().urlEncodingEnabled(false).auth().oauth2(Users.token("alice")).contentType(ContentType.JSON);
    }

    /** The prototype's "completed orders" starter query, as the designer sends it. */
    static String completedOrders(String totalCriteria, String totalField) {
        return """
                {
                  "sources": [{"alias": "Customers", "table": ["Customers"]}, {"alias": "Orders", "table": ["Orders"]}],
                  "joins": [{"type": "INNER", "left": {"source": "Customers", "column": "customer_id"},
                             "right": {"source": "Orders", "column": "customer_id"}}],
                  "columns": [
                    {"id": "c1", "table": "Customers", "field": "name", "alias": "customer"},
                    {"id": "c2", "table": "Customers", "field": "city"},
                    {"id": "c3", "table": "Orders", "field": "order_date", "sort": "DESC"},
                    {"id": "c4", "table": "Orders", "field": "%s"},
                    {"id": "c5", "table": "Orders", "field": "status"}
                  ],
                  "criteria": [{"c4": "%s", "c5": "'Completed'"}]
                }""".formatted(totalField, totalCriteria);
    }

    @Test
    void aDesignCompilesToCanonicalSqlAndCompleteTargetSql_valuesWrittenIn() {
        alice().body(completedOrders(">= 100", "total")).when().post("/api/connections/northstar/design:compile").then().log().ifValidationFails()
                .statusCode(200)
                .body("canonicalSql", containsString("WHERE \"Orders\".\"total\" >= 100 AND \"Orders\".\"status\" = 'Completed'"))
                .body("targetSql", equalTo("""
                        SELECT "Customers"."name" AS "customer", "Customers"."city", "Orders"."order_date", "Orders"."total", "Orders"."status"
                        FROM "Customers"
                        INNER JOIN "Orders" ON "Customers"."customer_id" = "Orders"."customer_id"
                        WHERE "Orders"."total" >= 100 AND "Orders"."status" = 'Completed'
                        ORDER BY "Orders"."order_date" DESC"""))
                .body("columns.name", contains("customer", "city", "order_date", "total", "status"))
                .body("columns.find { it.name == 'order_date' }.type", equalTo("DATE"))
                .body("diagnostics", empty());
    }

    @Test
    void aBadCriteriaCellIsReportedAgainstThatCell() {
        alice().body(completedOrders("Between 1", "total")).when().post("/api/connections/northstar/design:compile").then().log().ifValidationFails()
                .statusCode(422)
                .body("diagnostics[0].element", equalTo("cell"))
                .body("diagnostics[0].column", equalTo("c4"))
                .body("diagnostics[0].row", equalTo(0))
                .body("diagnostics[0].message", not(empty()));
    }

    @Test
    void anUnknownFieldIsReportedOnItsColumn() {
        alice().body(completedOrders(">= 100", "nope")).when().post("/api/connections/northstar/design:compile").then().log().ifValidationFails()
                .statusCode(422)
                .body("diagnostics[0].element", equalTo("column"))
                .body("diagnostics[0].column", equalTo("c4"))
                .body("diagnostics[0].message", containsString("nope"));
    }

    @Test
    void addingATableNamesItsCopyAndDrawsItsJoins() {
        String onCanvas = """
                {"design": {"sources": [{"alias": "Customers", "table": ["Customers"]}]}, "table": "%s"}""";
        alice().body(onCanvas.formatted("Orders")).when().post("/api/connections/northstar/design:add-source").then()
                .log().ifValidationFails()
                .statusCode(200)
                .body("source.alias", equalTo("Orders"))
                .body("source.table", contains("Orders"))
                .body("joins.type", contains("INNER"))
                .body("joins[0].left.source", equalTo("Customers"))
                .body("joins[0].left.column", equalTo("customer_id"))
                .body("joins[0].right.source", equalTo("Orders"))
                .body("joins[0].right.column", equalTo("customer_id"));
        alice().body(onCanvas.formatted("Customers")).when().post("/api/connections/northstar/design:add-source").then()
                .statusCode(200)
                .body("source.alias", equalTo("Customers_1"))
                .body("joins", empty());
    }

    @Test
    void addingAnUnknownTableIsRefused() {
        alice().body("""
                {"design": {"sources": []}, "table": "Nope"}""").when().post("/api/connections/northstar/design:add-source")
                .then().statusCode(422)
                .body("diagnostics[0].message", containsString("Nope"));
    }

    @Test
    void ambiguousOuterJoinsAreReportedOnTheSourceWhereTheyMeet() {
        alice().body("""
                {
                  "sources": [{"alias": "Customers", "table": ["Customers"]}, {"alias": "Orders", "table": ["Orders"]},
                              {"alias": "OrderItems", "table": ["OrderItems"]}],
                  "joins": [{"type": "LEFT", "left": {"source": "Customers", "column": "customer_id"},
                             "right": {"source": "Orders", "column": "customer_id"}},
                            {"type": "INNER", "left": {"source": "Orders", "column": "order_id"},
                             "right": {"source": "OrderItems", "column": "order_id"}}],
                  "columns": [{"id": "c1", "table": "Customers", "field": "name"}]
                }""").when().post("/api/connections/northstar/design:compile").then().log().ifValidationFails()
                .statusCode(422)
                .body("diagnostics[0].element", equalTo("source"))
                .body("diagnostics[0].source", equalTo("Orders"))
                .body("diagnostics[0].message", containsString("ambiguous outer joins"));
    }

    @Test
    void aJoinOnAnUnknownFieldIsReportedOnItsLine() {
        alice().body("""
                {
                  "sources": [{"alias": "Customers", "table": ["Customers"]}, {"alias": "Orders", "table": ["Orders"]}],
                  "joins": [{"type": "INNER", "left": {"source": "Customers", "column": "customer_id"},
                             "right": {"source": "Orders", "column": "nope"}}],
                  "columns": [{"id": "c1", "table": "Customers", "field": "name"}]
                }""").when().post("/api/connections/northstar/design:compile").then().log().ifValidationFails()
                .statusCode(422)
                .body("diagnostics[0].element", equalTo("join"))
                .body("diagnostics[0].join", equalTo(0))
                .body("diagnostics[0].message", containsString("nope"));
    }

    @Test
    void outputAllFieldsComesAndGoesThroughTheApi() {
        alice().body("""
                {
                  "sources": [{"alias": "Orders", "table": ["Orders"]}],
                  "columns": [{"id": "c1", "table": "Orders", "field": "total", "show": false}],
                  "criteria": [{"c1": ">= 100"}],
                  "outputAllFields": true
                }""").when().post("/api/connections/northstar/design:compile").then().log().ifValidationFails()
                .statusCode(200)
                .body("canonicalSql", containsString("SELECT *"))
                .body("columns", org.hamcrest.Matchers.hasSize(5));
        alice().body("""
                {"sql": "SELECT * FROM \\"Orders\\" WHERE \\"Orders\\".\\"total\\" >= 100"}""")
                .when().post("/api/connections/northstar/sql:parse").then().log().ifValidationFails()
                .statusCode(200)
                .body("design.outputAllFields", equalTo(true));
    }

    @Test
    void aDesignWithParametersListsThemWithTheirTypes_andKeepsThemInTheTargetSql() {
        alice().body("""
                {
                  "sources": [{"alias": "Orders", "table": ["Orders"]}],
                  "columns": [{"id": "c1", "table": "Orders", "field": "order_id"},
                              {"id": "c2", "table": "Orders", "field": "total", "show": false}],
                  "criteria": [{"c2": ">= :MinTotal"}]
                }""").when().post("/api/connections/northstar/design:compile").then().log().ifValidationFails()
                .statusCode(200)
                .body("parameters.name", contains("MinTotal"))
                .body("parameters.type", contains("BIGINT"))
                .body("targetSql", containsString(">= :MinTotal"));
    }
}
