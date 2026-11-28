package com.unify.querystudio.server;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.oidc.server.OidcWiremockTestResource;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;

/** Runs stopped at their timeout, and databases that cannot be reached. */
@QuarkusTest
@TestProfile(RunBoundsTest.Bounds.class)
@QuarkusTestResource(NorthstarConnection.class)
@QuarkusTestResource(OidcWiremockTestResource.class)
class RunBoundsTest {

    public static class Bounds implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "qs.run.timeout", "PT0.3S",
                    "qs.connections.missing.name", "Gone",
                    "qs.connections.missing.dialect", "sqlite",
                    "qs.connections.missing.jdbc-url", "jdbc:sqlite:/no/such/dir/secret-name.db",
                    "qs.connections.missing.use-roles", "user");
        }
    }

    @Test
    void aRunThatExceedsTheTimeoutIsStopped() {
        RunsResourceTest.run("alice", UUID.randomUUID().toString(), RunsResourceTest.SLOW, 10)
                .statusCode(504)
                .body("message", containsString("was stopped"));
    }

    @Test
    void anUnreachableDatabaseIsABadGateway_withoutItsLocation() {
        RunsResourceTest.as("alice").when().get("/api/connections/missing/catalog").then()
                .statusCode(502)
                .body("message", equalTo("The database could not be reached."))
                .body("message", not(containsString("secret-name")));
        RunsResourceTest.run("alice", UUID.randomUUID().toString(), "SELECT 1", 10).statusCode(200);
    }
}
