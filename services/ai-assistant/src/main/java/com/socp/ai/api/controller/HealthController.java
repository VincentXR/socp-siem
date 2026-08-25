package com.socp.ai.api.controller;

import com.socp.ai.api.request.*;
import com.socp.ai.config.AiRuntimeProperties;
import com.socp.platform.error.api.ApiResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Runtime health endpoint for the preview assistant service. */
@RestController
public class HealthController {
    private final AiRuntimeProperties properties;

    public HealthController(AiRuntimeProperties properties) {
        this.properties = properties;
    }

    @GetMapping("/health")
    public ApiResult<Map<String, Object>> health() {
        return ApiResult.ok(Map.of("service", "ai-assistant", "status", "UP",
                "maturity", properties.getMaturity()));
    }
}
