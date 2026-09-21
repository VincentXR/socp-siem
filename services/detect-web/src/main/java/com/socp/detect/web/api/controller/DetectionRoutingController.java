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
    private final com.socp.detect.web.routing.DetectionRoutingRuntime runtime;

    public DetectionRoutingController(DetectionRoutingPlanRegistry plans,
                                      com.socp.detect.web.routing.DetectionRoutingRuntime runtime) {
        this.plans = plans;
        this.runtime = runtime;
    }

    @RequireRole({"admin", "analyst"})
    @GetMapping
    public ApiResult<Map<String, Object>> plan() {
        var plan = plans.plan(TenantContext.require());
        Map<String, Object> response = new java.util.LinkedHashMap<>(plan.summary());
        response.put("deployment", runtime.summary(plan));
        boolean compatible = plans.topologyCompatible(TenantContext.require(), plan);
        response.put("topologyCompatible", compatible);
        if (!compatible) response.put("capabilityStatus", "TOPOLOGY_CONFLICT");
        return ApiResult.ok(Map.copyOf(response));
    }
}
