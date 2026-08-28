package com.socp.attack.web.api.controller;

import com.socp.attack.web.api.request.*;
import com.socp.attack.web.domain.Technique;
import com.socp.attack.web.persistence.store.AttackStore;
import com.socp.platform.audit.api.AuditOperation;
import com.socp.platform.auth.security.RequireRole;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * MITRE ATT&CK REST API（context-path /attack-web）。
 * 提供战术/技术目录查询与检测覆盖率计算（结合 DETECT 规则的 mitre 字段）。
 */
@RestController
@RequestMapping("/api/v1")
public class AttackController {

    private final AttackStore store;

    public AttackController(AttackStore store) {
        this.store = store;
    }

    @GetMapping("/tactics")
    public List<?> tactics() {
        return store.tactics();
    }

    @GetMapping("/techniques")
    public List<Technique> techniques(@RequestParam(required = false) String tactic) {
        if (tactic == null || tactic.isBlank()) return store.techniques();
        return store.techniques().stream().filter(t -> t.tactic().equals(tactic)).toList();
    }

    @GetMapping("/techniques/{id}")
    public Map<String, Object> technique(@PathVariable String id) {
        Technique t = store.technique(id);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("found", t != null);
        if (t != null) out.put("technique", t);
        return out;
    }

    @RequireRole({"admin", "analyst"})
    @AuditOperation(action = "UPDATE_ATTACK_TECHNIQUE", target = "attack")
    @PutMapping("/techniques/{id}")
    public Technique update(@PathVariable String id, @Valid @RequestBody TechniqueUpdateRequest body) {
        Technique updated = store.update(id, body.name(), body.tactic(), body.url(), body.description());
        if (updated == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "ATT&CK 技术不存在");
        return updated;
    }

    /**
     * 检测覆盖率：请求体 {"ruleTechniques": ["T1110","T1190"]}，
     * 返回每个战术的覆盖数/覆盖率、总体覆盖率、未覆盖技术列表。
     */
    @RequireRole({"admin", "analyst", "viewer"})
    @PostMapping("/coverage")
    public Map<String, Object> coverage(@Valid @RequestBody CoverageRequest body) {
        List<String> ruleTechs = body.ruleTechniques();
        Set<String> covered = Set.copyOf(ruleTechs);
        return store.coverage(covered);
    }

    @GetMapping("/stats")
    public Map<String, Object> stats() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tactics", store.tactics().size());
        out.put("techniques", store.techniques().size());
        return out;
    }

}
