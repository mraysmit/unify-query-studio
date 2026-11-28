package com.unify.querystudio.server;

import io.quarkus.test.oidc.server.OidcWiremockTestResource;

import java.util.Set;

/** Bearer tokens signed by the test identity provider. */
final class Users {

    private Users() {
    }

    /** A token for a regular user: the "user" group may use and run the Northstar connection. */
    static String token(String user) {
        return token(user, "user");
    }

    static String token(String user, String... groups) {
        return OidcWiremockTestResource.getAccessToken(user, Set.of(groups));
    }
}
