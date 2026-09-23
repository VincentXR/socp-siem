package com.socp.report.web.api.controller;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.socp.platform.auth.security.RequireRole;
import com.socp.platform.error.api.ApiResult;
import com.socp.platform.error.exception.ApiException;
import com.socp.report.web.domain.ReportSummary;
import com.socp.report.web.domain.ReportTrend;
import com.socp.report.web.service.ReportService;
import com.socp.report.web.persistence.store.ReportObjectStore;
import com.socp.platform.tenant.context.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * REPORT 报表 API：日报 + 7 日趋势 + MinIO 归档。
 */
@RestController
@RequestMapping("/api/v1/reports")
public class ReportController {

    static final int ARCHIVE_DEFAULT_LIMIT = 500;
    static final int ARCHIVE_MAX_LIMIT = 5_000;

    private static final Logger log = LoggerFactory.getLogger(ReportController.class);

    private final ReportService service;
    private final ReportObjectStore objectStore;
    private final ObjectMapper mapper;

    public ReportController(ReportService service, ReportObjectStore objectStore) {
        this.service = service;
        this.objectStore = objectStore;
        // This controller serializes report provenance (Instant) into archived
        // objects.  The application ObjectMapper is configured by Spring, but
        // this controller is also constructed directly in slice/unit tests and
        // must retain the same Java time contract there.
        this.mapper = new ObjectMapper().findAndRegisterModules();
    }

    @GetMapping("/daily")
    public ApiResult<ReportSummary> daily() {
        return ApiResult.ok(service.dailyReport());
    }

    @GetMapping("/trend7d")
    public ApiResult<ReportTrend> trend7d() {
        return ApiResult.ok(service.trend7d());
    }

    /** 归档：把当日日报 + 趋势快照上传 MinIO，返回对象 key；失败按 5xx 信封归一，原因只进日志。 */
    @PostMapping("/archive")
    @RequireRole({"admin", "analyst"})
    public ApiResult<Map<String, Object>> archive() {
        String day = ReportObjectStore.today();
        try {
            // Serialize both sources before one object PUT. Unique keys preserve earlier
            // successful snapshots across concurrent requests and ambiguous write failures.
            Map<String, Object> snapshot = new LinkedHashMap<>();
            snapshot.put("schemaVersion", 1);
            snapshot.put("day", day);
            snapshot.put("daily", service.dailyReport());
            snapshot.put("trend7d", service.trend7d());
            String key = tenantPrefix() + day + "/snapshot-" + UUID.randomUUID() + ".json";
            String archiveKey = objectStore.put(key, mapper.writeValueAsString(snapshot), "application/json");
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("archived", true);
            out.put("day", day);
            out.put("archiveKey", archiveKey);
            out.put("schemaVersion", 1);
            return ApiResult.ok(out);
        } catch (Exception failure) {
            log.error("报表归档失败 day={}", day, failure);
            throw ApiException.of(503, "报表归档失败，请稍后重试；若持续失败请联系运维核对对象存储与报表数据源");
        }
    }

    /** 归档列表（最近对象）。 */
    @GetMapping("/archive")
    @RequireRole({"admin", "analyst", "viewer"})
    public ApiResult<Map<String, Object>> archived(
            @RequestParam(defaultValue = "reports/") String prefix,
            @RequestParam(defaultValue = "500") int limit) {
        if (limit < 1 || limit > ARCHIVE_MAX_LIMIT) {
            throw ApiException.badRequest("limit must be between 1 and " + ARCHIVE_MAX_LIMIT);
        }
        String ownedPrefix = ownedPrefix(prefix);
        List<Map<String, Object>> items = objectStore.list(ownedPrefix, limit);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("prefix", ownedPrefix);
        out.put("count", items.size());
        out.put("limit", limit);
        out.put("truncated", items.size() >= limit);
        out.put("objects", items);
        return ApiResult.ok(out);
    }

    /** Source-compatible overload for scheduled/internal callers. */
    public ApiResult<Map<String, Object>> archived(String prefix) {
        String ownedPrefix = ownedPrefix(prefix);
        List<Map<String, Object>> items = objectStore.list(ownedPrefix);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("prefix", ownedPrefix);
        out.put("count", items.size());
        out.put("limit", ARCHIVE_DEFAULT_LIMIT);
        out.put("truncated", items.size() >= ARCHIVE_DEFAULT_LIMIT);
        out.put("objects", items);
        return ApiResult.ok(out);
    }

    /** 生成对象下载链接（7 天有效）。key 通过查询参数传（含斜杠，如 reports/20260809/daily.json）。 */
    @GetMapping("/archive/download")
    @RequireRole({"admin", "analyst", "viewer"})
    public ApiResult<Map<String, Object>> download(@RequestParam String key) {
        if (key == null || !key.startsWith(tenantPrefix()) || key.contains("..")) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.FORBIDDEN,
                    "report object is outside the current tenant");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("key", key);
        out.put("url", objectStore.presignedGet(key));
        return ApiResult.ok(out);
    }

    private static String tenantPrefix() {
        return "reports/" + TenantContext.require() + "/";
    }

    private static String ownedPrefix(String requested) {
        String base = tenantPrefix();
        if (requested == null || requested.isBlank() || "reports/".equals(requested)) return base;
        if (requested.contains("..") || !requested.startsWith(base)) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.FORBIDDEN,
                    "report prefix is outside the current tenant");
        }
        return requested;
    }
}
