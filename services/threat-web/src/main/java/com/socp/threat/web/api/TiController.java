package com.socp.threat.web.api;

import com.socp.threat.web.domain.Ioc;
import com.socp.threat.web.store.IocStore;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

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

    @PostMapping("/iocs")
    public Ioc create(@RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        List<String> tags = (List<String>) body.getOrDefault("tags", List.of());
        Ioc ioc = Ioc.of(
                str(body, "type"), str(body, "value"), str(body, "severity"),
                str(body, "source"), str(body, "description"), tags);
        return store.add(ioc);
    }

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
    @PostMapping("/iocs/match")
    public Map<String, Object> matchBulk(@RequestBody List<String> values) {
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
}
