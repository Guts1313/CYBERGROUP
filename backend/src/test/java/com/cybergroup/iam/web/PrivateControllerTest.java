package com.cybergroup.iam.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(PrivateController.class)
class PrivateControllerTest {

    @Autowired
    private MockMvc mvc;

    @Test
    void privateEndpoint_returnsOk() throws Exception {
        mvc.perform(get("/api/private"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.endpoint").value("private"));
    }
}
