package com.cybergroup.iam.web;

import com.cybergroup.iam.config.KeycloakRealmRoleConverter;
import com.cybergroup.iam.config.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.Arrays;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * W3 + W4 verification: every protected endpoint requires a valid JWT, and the
 * per-role rules from {@link PrivateController} resolve correctly.
 */
@WebMvcTest(PrivateController.class)
@Import({SecurityConfig.class, KeycloakRealmRoleConverter.class})
class PrivateControllerTest {

    @Autowired
    private MockMvc mvc;

    /** Required because oauth2ResourceServer auto-config needs a JwtDecoder bean even though we never call it (jwt() bypasses validation). */
    @MockBean
    private JwtDecoder jwtDecoder;

    // -- Authentication --------------------------------------------------------

    @Test
    void anyEndpoint_unauthenticated_isUnauthorized() throws Exception {
        mvc.perform(get("/api/private"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void anyEndpoint_authenticated_returnsClaims() throws Exception {
        mvc.perform(get("/api/private")
                .with(jwt().jwt(j -> j.subject("user-uuid").claim("preferred_username", "alice"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.subject").value("user-uuid"))
            .andExpect(jsonPath("$.username").value("alice"));
    }

    // -- Admin endpoint --------------------------------------------------------

    @Test
    void adminEndpoint_admin_isOk() throws Exception {
        mvc.perform(get("/api/private/admin").with(jwtWithRoles("admin")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.scope").value("admin"));
    }

    @Test
    void adminEndpoint_normalUser_isForbidden() throws Exception {
        mvc.perform(get("/api/private/admin").with(jwtWithRoles("normal")))
            .andExpect(status().isForbidden());
    }

    // -- IT endpoint -----------------------------------------------------------

    @Test
    void itEndpoint_itManager_isOk() throws Exception {
        mvc.perform(get("/api/private/it").with(jwtWithRoles("it_manager")))
            .andExpect(status().isOk());
    }

    @Test
    void itEndpoint_admin_isOk() throws Exception {
        mvc.perform(get("/api/private/it").with(jwtWithRoles("admin")))
            .andExpect(status().isOk());
    }

    @Test
    void itEndpoint_developer_isForbidden() throws Exception {
        mvc.perform(get("/api/private/it").with(jwtWithRoles("developer")))
            .andExpect(status().isForbidden());
    }

    // -- HR endpoint -----------------------------------------------------------

    @Test
    void hrEndpoint_hrManager_isOk() throws Exception {
        mvc.perform(get("/api/private/hr").with(jwtWithRoles("hr_manager")))
            .andExpect(status().isOk());
    }

    @Test
    void hrEndpoint_developer_isForbidden() throws Exception {
        mvc.perform(get("/api/private/hr").with(jwtWithRoles("developer")))
            .andExpect(status().isForbidden());
    }

    // -- Dev endpoint ----------------------------------------------------------

    @Test
    void devEndpoint_developer_isOk() throws Exception {
        mvc.perform(get("/api/private/dev").with(jwtWithRoles("developer")))
            .andExpect(status().isOk());
    }

    @Test
    void devEndpoint_normal_isForbidden() throws Exception {
        mvc.perform(get("/api/private/dev").with(jwtWithRoles("normal")))
            .andExpect(status().isForbidden());
    }

    // -- helpers ---------------------------------------------------------------

    private static RequestPostProcessor jwtWithRoles(String... roles) {
        GrantedAuthority[] authorities = Arrays.stream(roles)
            .map(r -> new SimpleGrantedAuthority("ROLE_" + r))
            .toArray(GrantedAuthority[]::new);
        return jwt().authorities(authorities);
    }
}
