package com.cybergroup.iam.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/private")
@Tag(name = "Private", description = "Placeholder protected endpoints (JWT validation lands in W3, RBAC in W4)")
public class PrivateController {

    @GetMapping
    @Operation(summary = "Placeholder protected endpoint — currently open until W3")
    public Map<String, String> privateEndpoint() {
        return Map.of("status", "ok", "endpoint", "private");
    }
}
