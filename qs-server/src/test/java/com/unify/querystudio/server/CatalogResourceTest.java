package com.unify.querystudio.server;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.oidc.server.OidcWiremockTestResource;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
@QuarkusTestResource(NorthstarConnection.class)
@QuarkusTestResource(OidcWiremockTestResource.class)
class CatalogResourceTest {

    @Test
    void theCatalogListsTablesColumnsKeysAndForeignKeys_asTheDesignerLoadsThem() {
        given().auth().oauth2(Users.token("alice")).when().get("/api/connections/northstar/catalog").then()
                .statusCode(200)
                .body("tables.name", containsInAnyOrder("Customers", "Orders", "OrderItems", "Products"))
                .body("tables.find { it.name == 'Orders' }.type", equalTo("TABLE"))
                .body("tables.find { it.name == 'Orders' }.columns.name",
                        contains("order_id", "customer_id", "order_date", "status", "total"))
                .body("tables.find { it.name == 'Orders' }.columns.find { it.name == 'order_date' }.type", equalTo("DATE"))
                .body("tables.find { it.name == 'Orders' }.columns.find { it.name == 'status' }.type", equalTo("VARCHAR"))
                .body("tables.find { it.name == 'Orders' }.primaryKey", contains("order_id"))
                .body("tables.find { it.name == 'Orders' }.foreignKeys[0].columns", contains("customer_id"))
                .body("tables.find { it.name == 'Orders' }.foreignKeys[0].referencedTable", equalTo("Customers"))
                .body("tables.find { it.name == 'Orders' }.foreignKeys[0].referencedColumns", contains("customer_id"));
    }

    @Test
    void anUnknownConnectionIsNotFound() {
        given().auth().oauth2(Users.token("alice")).when().get("/api/connections/nope/catalog").then().statusCode(404);
    }
}
