package com.socp.detect.web.api.controller;

import com.socp.detect.web.api.request.RuleDryRunRequest;
import com.socp.detect.web.config.DetectRuntimeRole;
import com.socp.detect.web.service.RuleDryRunService;
import com.socp.platform.auth.security.RequireRole;
import com.socp.platform.error.api.ApiResult;
import com.socp.platform.ratelimit.api.RateLimit;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.List;
import java.util.Map;

@RestController
@DetectRuntimeRole(DetectRuntimeRole.Role.API)
@RequestMapping("/api/v1/rules")
public class RuleDryRunController {
    private final RuleDryRunService service;

    public RuleDryRunController(RuleDryRunService service) { this.service = service; }

    @RequireRole({"admin", "analyst"})
    @RateLimit(permits = 2, seconds = 1)
    @PostMapping("/test")
    public ApiResult<List<Map<String, Object>>> test(@Valid @RequestBody RuleDryRunRequest request) {
        return ApiResult.ok(service.evaluate(request));
    }
}
