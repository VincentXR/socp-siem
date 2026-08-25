package com.socp.soc.api.controller;

import com.socp.soc.api.request.*;
import com.socp.platform.error.api.ApiResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Runtime health endpoint for tenant and platform metadata APIs. */
@RestController
public class HealthController {
    @GetMapping("/health")
    public ApiResult<Map<String, Object>> health() {
        return ApiResult.ok(Map.of("service", "soc-base", "status", "UP"));
    }
}
