package com.socp.soar.web.api.controller;

import com.socp.soar.web.api.request.*;
import com.socp.soar.web.config.SoarRuntimeProperties;
import com.socp.platform.error.api.ApiResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Runtime health endpoint for playbook execution and connector maturity. */
@RestController
public class HealthController {
    private final SoarRuntimeProperties properties;

    public HealthController(SoarRuntimeProperties properties) {
        this.properties = properties;
    }

    @GetMapping("/health")
    public ApiResult<Map<String, Object>> health() {
        return ApiResult.ok(Map.of("service", "soar-web", "status", "UP",
                "maturity", properties.getMaturity()));
    }
}
