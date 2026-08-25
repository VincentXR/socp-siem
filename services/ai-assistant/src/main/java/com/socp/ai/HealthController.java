package com.socp.ai;

import com.socp.ai.config.AiRuntimeProperties;
import com.socp.platform.error.ApiResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** 骨架健康/占位端点；业务接口按 P 提示词在其后扩展。 */
@RestController
public class HealthController {
    private final AiRuntimeProperties properties;

    public HealthController(AiRuntimeProperties properties) {
        this.properties = properties;
    }

    @GetMapping("/health")
    public ApiResult<Map<String, Object>> health() {
        return ApiResult.ok(Map.of("service", "ai-assistant", "status", "UP",
                "maturity", properties.getMaturity()));
    }
}
