package com.socp.detect.web.api.controller;


import com.socp.detect.web.service.DetectEngineService;
import com.socp.platform.error.api.ApiResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.health.HealthEndpoint;

import java.util.Map;
import java.util.LinkedHashMap;

/** Runtime health endpoint for rule evaluation and alert hand-off. */
@RestController
public class HealthController {
    private final ObjectProvider<HealthEndpoint> healthEndpoint;
    private final DetectEngineService detection;

    @org.springframework.beans.factory.annotation.Autowired
    public HealthController(ObjectProvider<HealthEndpoint> healthEndpoint,
                            DetectEngineService detection) {
        this.healthEndpoint = healthEndpoint;
        this.detection = detection;
    }

    /** Source-compatible constructor used by lightweight controller tests. */
    public HealthController(ObjectProvider<HealthEndpoint> healthEndpoint) {
        this(healthEndpoint, null);
    }

    @GetMapping("/health")
    public ApiResult<Map<String, Object>> health() {
        HealthEndpoint endpoint = healthEndpoint.getIfAvailable();
        String status = endpoint == null ? "UP" : endpoint.health().getStatus().getCode();
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("service", "detect-web");
        if (detection != null && !detection.isReady()) status = "DEGRADED";
        response.put("status", status);
        if (detection != null) {
            response.put("detectionRecovery", detection.recoveryStatus().name());
            response.put("detectionReady", detection.isReady());
        }
        return ApiResult.ok(response);
    }
}
