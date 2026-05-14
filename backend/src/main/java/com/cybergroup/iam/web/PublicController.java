package com.cybergroup.iam.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/public")
@Tag(name = "Public", description = "Endpoints accessible without authentication")
public class PublicController {

    @GetMapping
    @Operation(summary = "Unauthenticated probe")
    public Map<String, String> publicEndpoint() {
        return Map.of("status", "ok", "endpoint", "public");
    }
}
