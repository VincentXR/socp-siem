package com.socp.soc.api.controller;

import com.socp.platform.auth.security.RequireRole;
import com.socp.platform.error.api.ApiResult;
import com.socp.platform.error.api.PageResponse;
import com.socp.soc.service.AuditQueryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** Audit read API; persistence and fallback policy live in the application service. */
@RestController
@RequestMapping("/api/v1/audit")
public class AuditController {

    private final AuditQueryService queryService;

    public AuditController(AuditQueryService queryService) {
        this.queryService = queryService;
    }

    /** 最近审计记录查询。查询为"最新 N 条"语义，无游标翻页，契约上固定 page=1。 */
    @GetMapping("/records")
    @RequireRole({"admin", "analyst"})
    public ApiResult<PageResponse<Object>> records(
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(required = false) String action) {
        int size = Math.min(Math.max(limit, 1), 500);
        Map<String, Object> result = queryService.records(limit, action);
        List<?> records = (List<?>) result.getOrDefault("records", List.of());
        long total = result.get("total") instanceof Number count ? count.longValue() : 0;
        return ApiResult.ok(PageResponse.of(List.copyOf(records), total, 1, size));
    }

    @GetMapping("/stats")
    public ApiResult<Map<String, Object>> stats() {
        return ApiResult.ok(queryService.stats());
    }
}
