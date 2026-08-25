package com.socp.detect.web.api.controller;


import com.socp.detect.web.api.response.*;
import com.socp.detect.web.api.request.*;
import com.socp.platform.error.api.ApiResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Runtime health endpoint for rule evaluation and alert hand-off. */
@RestController
public class HealthController {
    @GetMapping("/health")
    public ApiResult<Map<String, Object>> health() {
        return ApiResult.ok(Map.of("service", "detect-web", "status", "UP"));
    }
}
