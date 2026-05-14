package com.cybergroup.iam.integration;

import com.cybergroup.iam.support.KeycloakIntegrationTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * E4 #59 — exercises the W5 access matrix end-to-end through real JWTs minted by a
 * Testcontainers Keycloak. This is the integration counterpart to the slice-level
 * PrivateControllerTest (which uses spring-security-test's mock JWT post-processor)
 * and the curl-based M4 #30 suite.
 */
class RbacIntegrationTest extends KeycloakIntegrationTestBase {

    private static final String ADMIN = "admin-demo";
    private static final String IT = "itmgr-demo";
    private static final String HR = "hrmgr-demo";
    private static final String DEV = "dev-demo";
    private static final String NORMAL = "normal-demo";

    @Test
    @DisplayName("public endpoint reachable without authentication")
    void publicEndpoint_anonymous_isOk() {
        assertThat(get("/api/public", null).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @ParameterizedTest(name = "anonymous {0} → 401")
    @CsvSource({
        "/api/private",
        "/api/private/admin",
        "/api/private/it",
        "/api/private/hr",
        "/api/private/dev"
    })
    @DisplayName("protected endpoints reject missing tokens")
    void protectedEndpoint_anonymous_isUnauthorized(String path) {
        assertThat(get(path, null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("garbage Bearer token → 401")
    void protectedEndpoint_garbageToken_isUnauthorized() {
        assertThat(get("/api/private", "not.a.real.jwt").getStatusCode())
            .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("tampered token signature → 401")
    void protectedEndpoint_tamperedToken_isUnauthorized() {
        String token = getAccessToken(ADMIN, "AdminDemo123!Init");
        // Flip the last byte of the signature so it no longer verifies.
        String tampered = token.substring(0, token.length() - 1)
            + (token.charAt(token.length() - 1) == 'A' ? 'B' : 'A');
        assertThat(get("/api/private", tampered).getStatusCode())
            .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    /**
     * The W5 access matrix as 25 parameterized rows. CSV: user,password,path,expectedStatus.
     */
    @ParameterizedTest(name = "{0} → {2} = {3}")
    @CsvSource({
        // admin-demo — full access
        "admin-demo,AdminDemo123!Init,/api/private,200",
        "admin-demo,AdminDemo123!Init,/api/private/admin,200",
        "admin-demo,AdminDemo123!Init,/api/private/it,200",
        "admin-demo,AdminDemo123!Init,/api/private/hr,200",
        "admin-demo,AdminDemo123!Init,/api/private/dev,200",
        // it_manager — admin/hr forbidden, it+dev allowed
        "itmgr-demo,ITMgrDemo123!Init,/api/private,200",
        "itmgr-demo,ITMgrDemo123!Init,/api/private/admin,403",
        "itmgr-demo,ITMgrDemo123!Init,/api/private/it,200",
        "itmgr-demo,ITMgrDemo123!Init,/api/private/hr,403",
        "itmgr-demo,ITMgrDemo123!Init,/api/private/dev,200",
        // hr_manager — only hr allowed
        "hrmgr-demo,HRMgrDemo123!Init,/api/private,200",
        "hrmgr-demo,HRMgrDemo123!Init,/api/private/admin,403",
        "hrmgr-demo,HRMgrDemo123!Init,/api/private/it,403",
        "hrmgr-demo,HRMgrDemo123!Init,/api/private/hr,200",
        "hrmgr-demo,HRMgrDemo123!Init,/api/private/dev,403",
        // developer — only dev allowed
        "dev-demo,DevDemo123!Init,/api/private,200",
        "dev-demo,DevDemo123!Init,/api/private/admin,403",
        "dev-demo,DevDemo123!Init,/api/private/it,403",
        "dev-demo,DevDemo123!Init,/api/private/hr,403",
        "dev-demo,DevDemo123!Init,/api/private/dev,200",
        // normal — only the any-authenticated endpoint
        "normal-demo,NormalDemo123!Init,/api/private,200",
        "normal-demo,NormalDemo123!Init,/api/private/admin,403",
        "normal-demo,NormalDemo123!Init,/api/private/it,403",
        "normal-demo,NormalDemo123!Init,/api/private/hr,403",
        "normal-demo,NormalDemo123!Init,/api/private/dev,403"
    })
    @DisplayName("W5 access matrix end-to-end through real JWTs")
    void accessMatrix(String user, String password, String path, int expected) {
        String token = getAccessToken(user, password);
        assertThat(get(path, token).getStatusCode().value()).isEqualTo(expected);
    }
}
