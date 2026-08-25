package com.socp.search.config.api;

import com.socp.search.config.domain.SinkTarget;
import com.socp.search.config.store.SinkTargetStore;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import com.socp.platform.auth.RequireRole;

/**
 * 输出目标 API：CRUD。
 */
@RestController
@RequestMapping("/api/v1/outputs")
public class SinkTargetController {

    private final SinkTargetStore store;

    public SinkTargetController(SinkTargetStore store) {
        this.store = store;
    }

    @GetMapping
    public List<SinkTarget> list() {
        return store.list();
    }

    @RequireRole({"admin", "analyst"})
    @PostMapping
    public SinkTarget create(@Valid @RequestBody SinkTargetRequest target) {
        return store.save(target.toDomain());
    }

    @RequireRole({"admin", "analyst"})
    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable String id) {
        return Map.of("removed", store.delete(id));
    }
}
