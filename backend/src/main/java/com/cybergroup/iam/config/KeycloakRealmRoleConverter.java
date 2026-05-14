package com.cybergroup.iam.config;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Maps Keycloak's {@code realm_access.roles} JWT claim to Spring Security authorities.
 * <p>
 * Keycloak places realm roles inside a nested object:
 * <pre>{@code
 * {
 *   "realm_access": { "roles": ["admin", "developer"] },
 *   ...
 * }
 * }</pre>
 * Spring Security's default JwtGrantedAuthoritiesConverter only reads flat claims like {@code scope},
 * so we substitute this converter to produce {@code ROLE_admin}, {@code ROLE_developer}, ...
 * which then satisfy {@code hasRole('admin')} expressions in {@link org.springframework.security.access.prepost.PreAuthorize}.
 */
public class KeycloakRealmRoleConverter implements Converter<Jwt, Collection<GrantedAuthority>> {

    private static final String REALM_ACCESS_CLAIM = "realm_access";
    private static final String ROLES_CLAIM = "roles";
    private static final String ROLE_PREFIX = "ROLE_";

    @Override
    public Collection<GrantedAuthority> convert(Jwt jwt) {
        Map<String, Object> realmAccess = jwt.getClaim(REALM_ACCESS_CLAIM);
        if (realmAccess == null) {
            return Collections.emptyList();
        }
        Object rolesObj = realmAccess.get(ROLES_CLAIM);
        if (!(rolesObj instanceof List<?> rolesList)) {
            return Collections.emptyList();
        }
        return rolesList.stream()
            .filter(String.class::isInstance)
            .map(String.class::cast)
            .map(role -> new SimpleGrantedAuthority(ROLE_PREFIX + role))
            .collect(Collectors.toUnmodifiableList());
    }
}
