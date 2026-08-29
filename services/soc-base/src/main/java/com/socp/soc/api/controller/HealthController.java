package com.socp.soc.api.controller;

import com.socp.soc.api.request.*;
import com.socp.platform.error.api.ApiResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.health.HealthEndpoint;

import java.util.Map;

/** Runtime health endpoint for tenant and platform metadata APIs. */
@RestController
public class HealthController {
    private final ObjectProvider<HealthEndpoint> healthEndpoint;

    public HealthController(ObjectProvider<HealthEndpoint> healthEndpoint) {
        this.healthEndpoint = healthEndpoint;
    }

    @GetMapping("/health")
    public ApiResult<Map<String, Object>> health() {
        HealthEndpoint endpoint = healthEndpoint.getIfAvailable();
        String status = endpoint == null ? "UP" : endpoint.health().getStatus().getCode();
        return ApiResult.ok(Map.of("service", "soc-base", "status", status));
    }
}
