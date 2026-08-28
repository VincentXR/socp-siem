package com.socp.threat.web.api.controller;

import com.socp.threat.web.api.request.*;
import com.socp.platform.audit.api.AuditOperation;
import com.socp.platform.auth.security.RequireRole;
import com.socp.threat.web.domain.Ioc;
import com.socp.threat.web.persistence.store.IocStore;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 威胁情报 REST API（context-path /threat-web）。
 * 提供 IOC 管理、单值/批量匹配（供检测与告警富化调用）。
 */
@RestController
@RequestMapping("/api/v1")
public class TiController {

    private final IocStore store;

    public TiController(IocStore store) {
        this.store = store;
    }

    @GetMapping("/iocs")
    public List<Ioc> list(@RequestParam(required = false) String type) {
        return store.list(type);
    }

    @RequireRole({"admin", "analyst"})
    @AuditOperation(action = "CREATE_IOC", target = "threat")
    @PostMapping("/iocs")
    public Ioc create(@Valid @RequestBody IocRequest body) {
        Ioc ioc = Ioc.of(
                body.type(), body.value(), body.severity(), body.source(), body.description(), body.tags());
        return store.add(ioc);
    }

    /** 批量导入 IOC：单条格式错误不会阻断同一批次的其他指标。 */
    @RequireRole({"admin", "analyst"})
    @AuditOperation(action = "IMPORT_IOC", target = "threat")
    @PostMapping("/iocs/import")
    public Map<String, Object> importIocs(@Valid @Size(max = 1000) @RequestBody List<@Valid IocImportRequest> rows) {
        List<String> errors = new java.util.ArrayList<>();
        int imported = 0;
        for (int index = 0; index < (rows == null ? 0 : rows.size()); index++) {
            IocImportRequest row = rows.get(index);
            if (row == null || row.value() == null || row.value().isBlank()) {
                errors.add("第 " + (index + 1) + " 行缺少情报值");
                continue;
            }
            Ioc ioc = Ioc.of(
                    valueOr(row.type(), "IP"), row.value(), valueOr(row.severity(), "MEDIUM"),
                    valueOr(row.source(), "import"), valueOr(row.description(), ""), row.tags() == null ? List.of() : row.tags());
            store.add(ioc);
            imported++;
        }
        return Map.of("imported", imported, "skipped", errors.size(), "errors", errors);
    }

    @RequireRole({"admin", "analyst"})
    @AuditOperation(action = "DELETE_IOC", target = "threat")
    @DeleteMapping("/iocs/{id}")
    public Map<String, Object> delete(@PathVariable String id) {
        return Map.of("removed", store.delete(id), "id", id);
    }

    /** 单值精确匹配：命中返回 IOC，未命中返回空映射。 */
    @GetMapping("/iocs/match")
    public Map<String, Object> matchOne(@RequestParam String value) {
        Ioc hit = store.match(value);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("value", value);
        out.put("matched", hit != null);
        if (hit != null) out.put("ioc", hit);
        return out;
    }

    /** 批量匹配：请求体为候选值数组，返回命中映射。 */
    @RequireRole({"admin", "analyst", "viewer"})
    @PostMapping("/iocs/match")
    public Map<String, Object> matchBulk(@Valid @Size(min = 1, max = 1000)
                                         @RequestBody List<@Size(max = 512) String> values) {
        Map<String, Ioc> hits = store.matchAll(values);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("checked", values.size());
        out.put("matched", hits.size());
        out.put("hits", hits);
        return out;
    }

    @GetMapping("/types")
    public List<String> types() {
        return List.of("IP", "DOMAIN", "URL", "SHA256", "MD5", "EMAIL");
    }

    @GetMapping("/stats")
    public Map<String, Object> stats() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("total", store.count());
        Map<String, Long> byType = new LinkedHashMap<>();
        for (Ioc i : store.all()) {
            byType.merge(i.type(), 1L, Long::sum);
        }
        m.put("byType", byType);
        return m;
    }

    private static String str(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v == null ? "" : String.valueOf(v);
    }

    private static String valueOr(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static List<String> tags(Map<String, Object> body) {
        Object raw = body.get("tags");
        if (raw instanceof List<?> values) {
            return values.stream().map(String::valueOf).map(String::trim).filter(value -> !value.isBlank()).toList();
        }
        if (raw instanceof String value && !value.isBlank()) {
            return java.util.Arrays.stream(value.split("[,，\\s]+"))
                    .map(String::trim).filter(tag -> !tag.isBlank()).toList();
        }
        return List.of();
    }
}
