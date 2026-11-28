package com.unify.querystudio.server;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.oidc.server.OidcWiremockTestResource;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

@QuarkusTest
@QuarkusTestResource(NorthstarConnection.class)
@QuarkusTestResource(OidcWiremockTestResource.class)
class SqlResourceTest {

    static RequestSpecification alice() {
        return given().urlEncodingEnabled(false).auth().oauth2(Users.token("alice")).contentType(ContentType.JSON);
    }

    static io.restassured.response.ValidatableResponse parse(String sql) {
        return alice().body(Map.of("sql", sql)).when().post("/api/connections/northstar/sql:parse").then()
                .log().ifValidationFails();
    }

    @Test
    void editedSqlBecomesADesignWithHiddenCriteriaColumns() {
        parse("""
                SELECT "Customers"."name" FROM "Customers"
                WHERE "Customers"."country" = 'UK' ORDER BY "Customers"."city\"""")
                .statusCode(200)
                .body("representable", equalTo(true))
                .body("design.sources.alias", contains("Customers"))
                .body("design.columns.field", contains("name", "country", "city"))
                .body("design.columns.show", contains(true, false, false))
                .body("design.columns[2].sort", equalTo("ASC"))
                .body("design.criteria[0].c2", equalTo("'UK'"))
                .body("diagnostics", empty());
    }

    @Test
    void aDesignSurvivesTheRoundTripThroughTheApi() {
        String design = DesignResourceTest.completedOrders(">= 100", "total");
        String sql = DesignResourceTest.alice().body(design).when().post("/api/connections/northstar/design:compile")
                .then().statusCode(200).extract().path("canonicalSql");
        Map<String, Object> parsed = parse(sql).statusCode(200).extract().path("design");
        String again = DesignResourceTest.alice().body(parsed).when().post("/api/connections/northstar/design:compile")
                .then().statusCode(200).extract().path("canonicalSql");
        org.assertj.core.api.Assertions.assertThat(again).isEqualTo(sql);
    }

    @Test
    void sqlTheDesignerCannotShowStaysSqlOnly_withTheReason() {
        parse("SELECT \"name\" FROM \"Customers\" UNION SELECT \"name\" FROM \"Products\"")
                .statusCode(200)
                .body("representable", equalTo(false))
                .body("reason", containsString("UNION"))
                .body("design", nullValue());
    }

    @Test
    void invalidSqlIsReportedWithItsPosition() {
        parse("SELECT \"nope\" FROM \"Customers\"")
                .statusCode(422)
                .body("diagnostics[0].element", equalTo("sql"))
                .body("diagnostics[0].line", equalTo(1))
                .body("diagnostics[0].startColumn", equalTo(8))
                .body("diagnostics[0].message", containsString("nope"));
    }
}
