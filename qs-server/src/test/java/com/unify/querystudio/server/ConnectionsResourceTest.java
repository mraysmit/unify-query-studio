package com.unify.querystudio.server;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.oidc.server.OidcWiremockTestResource;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.not;

@QuarkusTest
@QuarkusTestResource(NorthstarConnection.class)
@QuarkusTestResource(OidcWiremockTestResource.class)
class ConnectionsResourceTest {

    @Test
    void listsTheConfiguredConnections_neverTheirJdbcUrls() {
        given().auth().oauth2(Users.token("alice")).when().get("/api/connections").then()
                .statusCode(200)
                .body("id", contains("northstar"))
                .body("name", contains("Northstar sample"))
                .body("dialect", contains("sqlite"))
                .body("[0]", not(hasKey("jdbcUrl")));
    }

    @Test
    void everyApiCallNeedsASignedInUser() {
        given().when().get("/api/connections").then().statusCode(401);
        given().auth().oauth2("not-a-token").when().get("/api/connections").then().statusCode(401);
    }
}
