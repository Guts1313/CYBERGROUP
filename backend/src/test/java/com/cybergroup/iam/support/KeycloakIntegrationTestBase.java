package com.cybergroup.iam.support;

import dasniko.testcontainers.keycloak.KeycloakContainer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.util.Map;

/**
 * Shared Testcontainers base for backend integration tests against a real Keycloak.
 *
 * <p>The container is created once per JVM (singleton pattern) and shared across all
 * subclasses, so each test class doesn't pay the ~10s Keycloak boot cost. Spring rebuilds
 * its context per subclass, but the Keycloak issuer-uri stays constant.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class KeycloakIntegrationTestBase {

    protected static final KeycloakContainer KEYCLOAK;
    protected static final String REALM = "cybergroup";
    protected static final String CLIENT_ID = "iam-frontend";

    static {
        KEYCLOAK = new KeycloakContainer("quay.io/keycloak/keycloak:26.0")
            .withRealmImportFile("/cybergroup-realm-test.json");
        KEYCLOAK.start();
        // Container is intentionally never stopped — JVM exit takes care of it.
        // Sharing across test classes keeps suite runtime sane.
    }

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add(
            "spring.security.oauth2.resourceserver.jwt.issuer-uri",
            () -> KEYCLOAK.getAuthServerUrl() + "/realms/" + REALM
        );
    }

    @LocalServerPort
    protected int port;

    @Autowired
    protected TestRestTemplate rest;

    /**
     * Get a real access token from Keycloak via the Resource Owner Password Credentials grant
     * (enabled on iam-frontend in the test realm only).
     */
    protected String getAccessToken(String username, String password) {
        String tokenEndpoint = KEYCLOAK.getAuthServerUrl() + "/realms/" + REALM
            + "/protocol/openid-connect/token";

        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("grant_type", "password");
        body.add("client_id", CLIENT_ID);
        body.add("username", username);
        body.add("password", password);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        @SuppressWarnings("unchecked")
        Map<String, Object> response = rest.postForObject(
            tokenEndpoint, new HttpEntity<>(body, headers), Map.class);

        if (response == null || !response.containsKey("access_token")) {
            throw new IllegalStateException("token response missing access_token: " + response);
        }
        return (String) response.get("access_token");
    }

    /** GET against the running backend with an optional Bearer token. */
    protected ResponseEntity<String> get(String path, String tokenOrNull) {
        HttpHeaders headers = new HttpHeaders();
        if (tokenOrNull != null) {
            headers.setBearerAuth(tokenOrNull);
        }
        return rest.exchange(
            "http://localhost:" + port + path,
            HttpMethod.GET,
            new HttpEntity<>(headers),
            String.class
        );
    }
}
