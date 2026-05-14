package com.cybergroup.iam.web;

import com.cybergroup.iam.config.KeycloakRealmRoleConverter;
import com.cybergroup.iam.config.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(PublicController.class)
@Import({SecurityConfig.class, KeycloakRealmRoleConverter.class})
class PublicControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockBean
    private JwtDecoder jwtDecoder;

    @Test
    void publicEndpoint_anonymous_returnsOk() throws Exception {
        mvc.perform(get("/api/public"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("ok"))
            .andExpect(jsonPath("$.endpoint").value("public"));
    }
}
