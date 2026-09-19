package com.socp.asset.web.api.controller;

import com.socp.platform.error.api.ApiResult;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.health.HealthEndpoint;

import java.util.Map;

/** Runtime health endpoint for asset inventory and collection ingress. */
@RestController
public class HealthController {
    private final ObjectProvider<HealthEndpoint> healthEndpoint;

    public HealthController(ObjectProvider<HealthEndpoint> healthEndpoint) {
        this.healthEndpoint = healthEndpoint;
    }

    @GetMapping("/health")
    public ResponseEntity<ApiResult<Map<String, Object>>> health() {
        HealthEndpoint endpoint = healthEndpoint.getIfAvailable();
        String status = endpoint == null ? "UP" : endpoint.health().getStatus().getCode();
        HttpStatus code = "UP".equalsIgnoreCase(status) ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE;
        return ResponseEntity.status(code)
                .body(ApiResult.ok(Map.of("service", "asset-web", "status", status)));
    }
}
