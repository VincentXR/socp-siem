package com.socp.detect.model.api.controller;

import com.socp.detect.model.api.request.*;
import com.socp.platform.error.api.ApiResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Runtime health endpoint for secondary detection analysis. */
@RestController
public class HealthController {
    @GetMapping("/health")
    public ApiResult<Map<String, Object>> health() {
        return ApiResult.ok(Map.of("service", "detect-model", "status", "UP"));
    }
}
