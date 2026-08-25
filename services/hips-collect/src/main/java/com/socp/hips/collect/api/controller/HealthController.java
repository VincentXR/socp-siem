package com.socp.hips.collect.api.controller;

import com.socp.hips.collect.api.request.*;
import com.socp.platform.error.api.ApiResult;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Runtime health endpoint for the standalone endpoint collector launcher. */
@RestController
public class HealthController {
    @Value("${socp.maturity:demo}")
    private String maturity;

    @GetMapping("/health")
    public ApiResult<Map<String, Object>> health() {
        return ApiResult.ok(Map.of("service", "hips-collect", "status", "UP", "maturity", maturity));
    }
}
