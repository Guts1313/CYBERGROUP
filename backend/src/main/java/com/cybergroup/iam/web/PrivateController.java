package com.cybergroup.iam.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * W3 #18 — every endpoint here requires a valid JWT (enforced by {@link com.cybergroup.iam.config.SecurityConfig}).
 * W4 #26 — sub-routes apply per-role authorization via {@link PreAuthorize}.
 *
 * Roles correspond to the realm roles in {@code keycloak/realm-import/cybergroup-realm.json}:
 * {@code admin}, {@code it_manager}, {@code hr_manager}, {@code developer}, {@code normal}.
 *
 * The matrix below is a starting point — the authoritative role/access matrix lives in C2 #34
 * and W5 #27.
 */
@RestController
@RequestMapping("/api/private")
@Tag(name = "Private", description = "JWT-protected endpoints with per-role authorization")
@SecurityRequirement(name = "bearerAuth")
public class PrivateController {

    @GetMapping
    @Operation(summary = "Any authenticated user — echoes claims from the JWT")
    public Map<String, Object> any(@AuthenticationPrincipal Jwt jwt) {
        // LinkedHashMap (vs Map.of) so missing claims don't trigger NPEs and ordering is stable.
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("endpoint", "private");
        claims.put("subject", jwt.getSubject());
        claims.put("username", jwt.getClaimAsString("preferred_username"));
        if (jwt.getIssuer() != null) {
            claims.put("issuer", jwt.getIssuer().toString());
        }
        return claims;
    }

    @GetMapping("/admin")
    @PreAuthorize("hasRole('admin')")
    @Operation(summary = "Admin role only")
    public Map<String, String> adminOnly() {
        return Map.of("scope", "admin");
    }

    @GetMapping("/it")
    @PreAuthorize("hasAnyRole('it_manager', 'admin')")
    @Operation(summary = "IT Manager (and Admin) only")
    public Map<String, String> itOnly() {
        return Map.of("scope", "it_manager");
    }

    @GetMapping("/hr")
    @PreAuthorize("hasAnyRole('hr_manager', 'admin')")
    @Operation(summary = "HR Manager (and Admin) only")
    public Map<String, String> hrOnly() {
        return Map.of("scope", "hr_manager");
    }

    @GetMapping("/dev")
    @PreAuthorize("hasAnyRole('developer', 'it_manager', 'admin')")
    @Operation(summary = "Developer (and IT Manager / Admin) only")
    public Map<String, String> devOnly() {
        return Map.of("scope", "developer");
    }
}
