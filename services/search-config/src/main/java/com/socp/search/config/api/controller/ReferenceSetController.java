package com.socp.search.config.api.controller;





import com.socp.search.config.domain.ReferenceSet;
import com.socp.search.config.api.request.ReferenceEntryRequest;
import com.socp.search.config.api.request.ReferenceSetCreateRequest;
import com.socp.search.config.persistence.store.ReferenceSetStore;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import com.socp.platform.auth.security.RequireRole;
import jakarta.validation.Valid;

/**
 * 查找表 / 参考数据集 REST API（context-path /search-config）。
 * 用于事件富化与检测条件引用，对标大厂 SIEM 的 Lookup / Watchlist。
 */
@RestController
@RequestMapping("/api/v1/reference-sets")
public class ReferenceSetController {

    private final ReferenceSetStore store;

    public ReferenceSetController(ReferenceSetStore store) {
        this.store = store;
    }

    @GetMapping
    public List<ReferenceSet> list() {
        return store.list();
    }

    @RequireRole({"admin", "analyst"})
    @PostMapping
    public ReferenceSet create(@Valid @RequestBody ReferenceSetCreateRequest body) {
        return store.add(ReferenceSet.of(body.name(), body.description(), body.entries()));
    }

    @RequireRole({"admin", "analyst"})
    @PostMapping("/{id}/entries")
    public Map<String, Object> addEntry(@PathVariable String id, @Valid @RequestBody ReferenceEntryRequest body) {
        ReferenceSet rs = store.get(id);
        if (rs == null) return Map.of("error", "not_found");
        List<String> entries = new java.util.ArrayList<>(rs.entries());
        String v = body.value();
        if (!entries.contains(v)) entries.add(v);
        store.add(new ReferenceSet(rs.id(), rs.name(), rs.description(), List.copyOf(entries)));
        return Map.of("ok", true, "size", entries.size());
    }

    @RequireRole({"admin", "analyst"})
    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable String id) {
        return Map.of("removed", store.delete(id), "id", id);
    }

    @GetMapping("/{name}/contains")
    public Map<String, Object> contains(@PathVariable String name, @org.springframework.web.bind.annotation.RequestParam String value) {
        return Map.of("name", name, "value", value, "contains", store.contains(name, value));
    }

    private static String str(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v == null ? "" : String.valueOf(v);
    }
}
