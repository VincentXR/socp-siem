package com.socp.soar.web.api.controller;

import com.socp.soar.web.api.request.*;
import com.socp.soar.web.config.SoarRuntimeProperties;
import com.socp.platform.error.api.ApiResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.health.HealthEndpoint;

import java.util.Map;

/** Runtime health endpoint for playbook execution and connector maturity. */
@RestController
public class HealthController {
    private final SoarRuntimeProperties properties;
    private final ObjectProvider<HealthEndpoint> healthEndpoint;

    public HealthController(SoarRuntimeProperties properties, ObjectProvider<HealthEndpoint> healthEndpoint) {
        this.properties = properties;
        this.healthEndpoint = healthEndpoint;
    }

    @GetMapping("/health")
    public ApiResult<Map<String, Object>> health() {
        HealthEndpoint endpoint = healthEndpoint.getIfAvailable();
        String status = endpoint == null ? "UP" : endpoint.health().getStatus().getCode();
        return ApiResult.ok(Map.of("service", "soar-web", "status", status,
                "maturity", properties.getMaturity()));
    }
}
