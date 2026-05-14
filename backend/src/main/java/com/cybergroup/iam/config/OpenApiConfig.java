package com.cybergroup.iam.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI cybergroupOpenAPI() {
        return new OpenAPI()
            .info(new Info()
                .title("CYBERGROUP IAM Backend API")
                .description("Demo backend for the IAM-with-Keycloak project. "
                    + "Phase A scaffold — JWT validation lands in W3, RBAC in W4.")
                .version("0.1.0"));
    }
}
