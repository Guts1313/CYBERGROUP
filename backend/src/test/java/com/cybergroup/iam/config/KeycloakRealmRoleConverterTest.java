package com.cybergroup.iam.config;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class KeycloakRealmRoleConverterTest {

    private final KeycloakRealmRoleConverter converter = new KeycloakRealmRoleConverter();

    @Test
    void convert_extractsRealmRoles_andPrefixes() {
        Jwt jwt = jwtWithClaims(Map.of("realm_access", Map.of("roles", List.of("admin", "developer"))));

        Collection<GrantedAuthority> authorities = converter.convert(jwt);

        assertThat(authorities)
            .extracting(GrantedAuthority::getAuthority)
            .containsExactlyInAnyOrder("ROLE_admin", "ROLE_developer");
    }

    @Test
    void convert_missingRealmAccess_returnsEmpty() {
        Jwt jwt = jwtWithClaims(Map.of("sub", "user-uuid"));

        assertThat(converter.convert(jwt)).isEmpty();
    }

    @Test
    void convert_realmAccessWithoutRolesKey_returnsEmpty() {
        Jwt jwt = jwtWithClaims(Map.of("realm_access", Map.of("other", "value")));

        assertThat(converter.convert(jwt)).isEmpty();
    }

    @Test
    void convert_skipsNonStringRoleEntries() {
        Jwt jwt = jwtWithClaims(Map.of("realm_access", Map.of("roles", List.of("admin", 42, "developer"))));

        assertThat(converter.convert(jwt))
            .extracting(GrantedAuthority::getAuthority)
            .containsExactlyInAnyOrder("ROLE_admin", "ROLE_developer");
    }

    private static Jwt jwtWithClaims(Map<String, Object> claims) {
        return new Jwt(
            "token-value",
            Instant.now(),
            Instant.now().plusSeconds(300),
            Map.of("alg", "RS256"),
            claims
        );
    }
}
