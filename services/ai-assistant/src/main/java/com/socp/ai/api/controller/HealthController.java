package com.socp.ai.api.controller;

import com.socp.ai.config.AiRuntimeProperties;
import com.socp.platform.error.api.ApiResult;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.health.HealthEndpoint;

import java.util.Map;

/** Runtime health endpoint for the preview assistant service. */
@RestController
public class HealthController {
    private final AiRuntimeProperties properties;
    private final ObjectProvider<HealthEndpoint> healthEndpoint;

    public HealthController(AiRuntimeProperties properties, ObjectProvider<HealthEndpoint> healthEndpoint) {
        this.properties = properties;
        this.healthEndpoint = healthEndpoint;
    }

    @GetMapping("/health")
    public ResponseEntity<ApiResult<Map<String, Object>>> health() {
        HealthEndpoint endpoint = healthEndpoint.getIfAvailable();
        String status = endpoint == null ? "UP" : endpoint.health().getStatus().getCode();
        HttpStatus code = "UP".equalsIgnoreCase(status) ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE;
        return ResponseEntity.status(code).body(ApiResult.ok(Map.of("service", "ai-assistant", "status", status,
                "maturity", properties.getMaturity())));
    }
}
