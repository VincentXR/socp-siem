package com.socp.ai;

import com.socp.platform.error.ApiResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.beans.factory.annotation.Value;

import java.util.Map;

/** 骨架健康/占位端点；业务接口按 P 提示词在其后扩展。 */
@RestController
public class HealthController {
    @Value("${socp.ai.maturity:preview}")
    private String maturity;

    @GetMapping("/health")
    public ApiResult<Map<String, Object>> health() {
        return ApiResult.ok(Map.of("service", "ai-assistant", "status", "UP", "maturity", maturity));
    }
}
