package com.socp.detect.web.api.controller;

import com.socp.detect.web.config.DetectRuntimeRole;
import com.socp.detect.web.routing.DetectionRoutingPlanRegistry;
import com.socp.platform.auth.security.RequireRole;
import com.socp.platform.error.api.ApiResult;
import com.socp.platform.tenant.context.TenantContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Exposes the executable cross-dimension routing compatibility contract. */
@RestController
@DetectRuntimeRole(DetectRuntimeRole.Role.WORKER)
@RequestMapping("/api/v1/routing-plan")
public class DetectionRoutingController {

    private final DetectionRoutingPlanRegistry plans;

    public DetectionRoutingController(DetectionRoutingPlanRegistry plans) {
        this.plans = plans;
    }

    @RequireRole({"admin", "analyst"})
    @GetMapping
    public ApiResult<Map<String, Object>> plan() {
        return ApiResult.ok(plans.plan(TenantContext.require()).summary());
    }
}
