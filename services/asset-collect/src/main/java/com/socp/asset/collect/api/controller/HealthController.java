package com.socp.asset.collect.api.controller;

import com.socp.asset.collect.api.request.*;
import com.socp.platform.error.api.ApiResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.health.HealthEndpoint;

import java.util.Map;

/** Runtime health endpoint for the standalone collector launcher. */
@RestController
public class HealthController {
    private final ObjectProvider<HealthEndpoint> healthEndpoint;
    @Value("${socp.maturity:demo}")
    private String maturity;

    public HealthController(ObjectProvider<HealthEndpoint> healthEndpoint) {
        this.healthEndpoint = healthEndpoint;
    }

    @GetMapping("/health")
    public ApiResult<Map<String, Object>> health() {
        return ApiResult.ok(Map.of("service", "asset-collect", "status", status(), "maturity", maturity));
    }

    private String status() {
        HealthEndpoint endpoint = healthEndpoint.getIfAvailable();
        return endpoint == null ? "UP" : endpoint.health().getStatus().getCode();
    }
}
