package com.cybergroup.iam.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Admin-only IAM dashboard. Surfaces Keycloak session data as live evidence that
 * authentication is working — i.e. who is logged in right now.
 *
 * <p>Auth model: the caller must hold the realm {@code admin} role. The admin's own
 * bearer token is relayed to Keycloak's Admin REST API, so the admin needs the
 * {@code realm-management} roles {@code view-users} + {@code view-clients}. No service
 * account or client secret is stored in the backend.
 */
@RestController
@RequestMapping("/api/admin")
@PreAuthorize("hasRole('admin')")
@Tag(name = "Admin", description = "Admin-only IAM dashboard (active sessions)")
@SecurityRequirement(name = "bearerAuth")
public class AdminController {

    private static final ParameterizedTypeReference<List<Map<String, Object>>> LIST_OF_MAPS =
            new ParameterizedTypeReference<>() {};

    private final RestClient http = RestClient.create();
    private final String adminBase;   // {keycloak}/admin/realms/{realm}

    public AdminController(
            @Value("${app.keycloak.base-url:http://localhost:8080}") String keycloakBaseUrl,
            @Value("${app.keycloak.realm:cybergroup}") String realm) {
        this.adminBase = keycloakBaseUrl + "/admin/realms/" + realm;
    }

    /** Currently active user sessions: username, IP, login time, last activity. */
    @GetMapping("/sessions")
    @Operation(summary = "Active user sessions (who is currently logged in)")
    public List<Map<String, Object>> activeSessions(@AuthenticationPrincipal Jwt jwt) {
        String bearer = "Bearer " + jwt.getTokenValue();
        try {
            // Keycloak lists sessions per client — resolve the SPA client's UUID first.
            List<Map<String, Object>> clients = http.get()
                    .uri(adminBase + "/clients?clientId=iam-frontend")
                    .header("Authorization", bearer)
                    .retrieve()
                    .body(LIST_OF_MAPS);
            if (clients == null || clients.isEmpty()) {
                return List.of();
            }
            String clientUuid = String.valueOf(clients.get(0).get("id"));

            List<Map<String, Object>> sessions = http.get()
                    .uri(adminBase + "/clients/" + clientUuid + "/user-sessions")
                    .header("Authorization", bearer)
                    .retrieve()
                    .body(LIST_OF_MAPS);
            if (sessions == null) {
                return List.of();
            }

            // Project to a small, stable shape for the dashboard.
            List<Map<String, Object>> out = new ArrayList<>();
            for (Map<String, Object> s : sessions) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("username", s.get("username"));
                row.put("ipAddress", s.get("ipAddress"));
                row.put("start", s.get("start"));            // epoch millis
                row.put("lastAccess", s.get("lastAccess"));  // epoch millis
                out.add(row);
            }
            return out;
        } catch (RestClientResponseException e) {
            // surface Keycloak's status (e.g. 403 if view-users is missing) to the caller
            throw new ResponseStatusException(e.getStatusCode(),
                    "Keycloak admin API returned " + e.getStatusCode());
        }
    }

    /** Recent authentication events (login / logout / failures) — the audit trail. */
    @GetMapping("/events")
    @Operation(summary = "Recent login events (authentication audit trail)")
    public List<Map<String, Object>> loginEvents(@AuthenticationPrincipal Jwt jwt) {
        String bearer = "Bearer " + jwt.getTokenValue();
        try {
            List<Map<String, Object>> events = http.get()
                    .uri(adminBase + "/events?type=LOGIN&type=LOGIN_ERROR&type=LOGOUT&type=LOGOUT_ERROR&max=50")
                    .header("Authorization", bearer)
                    .retrieve()
                    .body(LIST_OF_MAPS);
            if (events == null) {
                return List.of();
            }
            List<Map<String, Object>> out = new ArrayList<>();
            for (Map<String, Object> e : events) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("time", e.get("time"));            // epoch millis
                row.put("type", e.get("type"));
                // login events carry the attempted username under details.username
                Object details = e.get("details");
                Object username = (details instanceof Map<?, ?> d) ? d.get("username") : null;
                row.put("username", username != null ? username : e.get("userId"));
                row.put("ipAddress", e.get("ipAddress"));
                row.put("clientId", e.get("clientId"));
                row.put("error", e.get("error"));          // present on *_ERROR events
                out.add(row);
            }
            return out;
        } catch (RestClientResponseException e) {
            throw new ResponseStatusException(e.getStatusCode(),
                    "Keycloak admin API returned " + e.getStatusCode());
        }
    }
}
