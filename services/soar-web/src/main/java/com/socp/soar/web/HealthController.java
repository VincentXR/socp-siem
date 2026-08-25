package com.socp.soar.web;

import com.socp.soar.web.config.SoarRuntimeProperties;
import com.socp.platform.error.ApiResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** 骨架健康/占位端点；业务接口按 P 提示词在其后扩展。 */
@RestController
public class HealthController {
    private final SoarRuntimeProperties properties;

    public HealthController(SoarRuntimeProperties properties) {
        this.properties = properties;
    }

    @GetMapping("/health")
    public ApiResult<Map<String, Object>> health() {
        return ApiResult.ok(Map.of("service", "soar-web", "status", "UP",
                "maturity", properties.getMaturity()));
    }
}
