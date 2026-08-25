package com.socp.asset.collect.api;

import com.socp.platform.error.ApiResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.beans.factory.annotation.Value;

import java.util.Map;

/** Runtime health endpoint for the standalone collector launcher. */
@RestController
public class HealthController {
    @Value("${socp.maturity:demo}")
    private String maturity;

    @GetMapping("/health")
    public ApiResult<Map<String, Object>> health() {
        return ApiResult.ok(Map.of("service", "asset-collect", "status", "UP", "maturity", maturity));
    }
}
