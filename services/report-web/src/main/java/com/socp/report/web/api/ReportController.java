package com.socp.report.web.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.socp.platform.auth.RequireRole;
import com.socp.report.web.model.ReportSummary;
import com.socp.report.web.service.ReportService;
import com.socp.report.web.store.ReportObjectStore;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * REPORT 报表 API：日报 + 7 日趋势 + MinIO 归档。
 */
@RestController
@RequestMapping("/api/v1/reports")
public class ReportController {

    private final ReportService service;
    private final ReportObjectStore objectStore;
    private final ObjectMapper mapper;

    public ReportController(ReportService service, ReportObjectStore objectStore) {
        this.service = service;
        this.objectStore = objectStore;
        this.mapper = new ObjectMapper();
    }

    @GetMapping("/daily")
    public ReportSummary daily() {
        return service.dailyReport();
    }

    @GetMapping("/trend7d")
    public Map<String, Object> trend7d() {
        return service.trend7d();
    }

    /** 归档：把当日日报 + 趋势快照上传 MinIO，返回对象 key。 */
    @PostMapping("/archive")
    @RequireRole({"admin", "analyst"})
    public Map<String, Object> archive() {
        String day = ReportObjectStore.today();
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            String dailyJson = mapper.writeValueAsString(service.dailyReport());
            String trendJson = mapper.writeValueAsString(service.trend7d());
            String dailyKey = objectStore.put("reports/" + day + "/daily.json", dailyJson, "application/json");
            String trendKey = objectStore.put("reports/" + day + "/trend7d.json", trendJson, "application/json");
            out.put("archived", true);
            out.put("day", day);
            out.put("dailyKey", dailyKey);
            out.put("trendKey", trendKey);
        } catch (Exception e) {
            out.put("archived", false);
            out.put("error", e.getMessage());
        }
        return out;
    }

    /** 归档列表（最近对象）。 */
    @GetMapping("/archive")
    public Map<String, Object> archived(@RequestParam(defaultValue = "reports/") String prefix) {
        List<Map<String, Object>> items = objectStore.list(prefix);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("prefix", prefix);
        out.put("count", items.size());
        out.put("objects", items);
        return out;
    }

    /** 生成对象下载链接（7 天有效）。key 通过查询参数传（含斜杠，如 reports/20260809/daily.json）。 */
    @GetMapping("/archive/download")
    public Map<String, Object> download(@RequestParam String key) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("key", key);
        out.put("url", objectStore.presignedGet(key));
        return out;
    }
}
