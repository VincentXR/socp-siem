package com.socp.attack.web.api.controller;

import com.socp.attack.web.api.request.CoverageRequest;
import com.socp.attack.web.api.request.TechniqueUpdateRequest;
import com.socp.attack.web.domain.Tactic;
import com.socp.attack.web.domain.Technique;
import com.socp.attack.web.persistence.store.AttackStore;
import com.socp.platform.audit.api.AuditOperation;
import com.socp.platform.auth.security.RequireRole;
import com.socp.platform.error.api.ApiResult;
import com.socp.platform.error.api.PageResponse;
import org.springframework.beans.factory.annotation.Value;
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
    private final int maxListSize;

    public AttackController(AttackStore store,
                            @Value("${socp.web.list-max-size:500}") int maxListSize) {
        this.store = store;
        this.maxListSize = maxListSize;
    }

    /** 战术列表：租户级分页（page 从 1 起，size 上限 socp.web.list-max-size，默认 500）。 */
    @GetMapping("/tactics")
    public ApiResult<PageResponse<Tactic>> tactics(@RequestParam(defaultValue = "1") int page,
                                                   @RequestParam(defaultValue = "500") int size) {
        requireValidRange(page, size);
        List<Tactic> all = store.tactics();
        int from = Math.min((page - 1) * size, all.size());
        int to = Math.min(from + size, all.size());
        return ApiResult.ok(PageResponse.of(all.subList(from, to), all.size(), page, size));
    }

    /** 技术列表：支持 tactic 过滤 + 租户级分页（page 从 1 起，size 上限 socp.web.list-max-size）。 */
    @GetMapping("/techniques")
    public ApiResult<PageResponse<Technique>> techniques(@RequestParam(required = false) String tactic,
                                                         @RequestParam(defaultValue = "1") int page,
                                                         @RequestParam(defaultValue = "500") int size) {
        requireValidRange(page, size);
        List<Technique> all = store.techniques();
        if (tactic != null && !tactic.isBlank()) {
            all = all.stream().filter(t -> t.tactic().equals(tactic)).toList();
        }
        int from = Math.min((page - 1) * size, all.size());
        int to = Math.min(from + size, all.size());
        return ApiResult.ok(PageResponse.of(all.subList(from, to), all.size(), page, size));
    }

    @GetMapping("/techniques/{id}")
    public ApiResult<Map<String, Object>> technique(@PathVariable String id) {
        Technique t = store.technique(id);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("found", t != null);
        if (t != null) out.put("technique", t);
        return ApiResult.ok(out);
    }

    @RequireRole({"admin", "analyst"})
    @AuditOperation(action = "UPDATE_ATTACK_TECHNIQUE", target = "attack")
    @PutMapping("/techniques/{id}")
    public ApiResult<Technique> update(@PathVariable String id, @Valid @RequestBody TechniqueUpdateRequest body) {
        Technique updated = store.update(id, body.name(), body.tactic(), body.url(), body.description());
        if (updated == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "ATT&CK 技术不存在");
        return ApiResult.ok(updated);
    }

    /**
     * 检测覆盖率：请求体 {"ruleTechniques": ["T1110","T1190"]}，
     * 返回每个战术的覆盖数/覆盖率、总体覆盖率、未覆盖技术列表。
     */
    @RequireRole({"admin", "analyst", "viewer"})
    @PostMapping("/coverage")
    public ApiResult<Map<String, Object>> coverage(@Valid @RequestBody CoverageRequest body) {
        List<String> ruleTechs = body.ruleTechniques();
        Set<String> covered = Set.copyOf(ruleTechs);
        return ApiResult.ok(store.coverage(covered));
    }

    @GetMapping("/stats")
    public ApiResult<Map<String, Object>> stats() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tactics", store.tactics().size());
        out.put("techniques", store.techniques().size());
        return ApiResult.ok(out);
    }

    private void requireValidRange(int page, int size) {
        if (page < 1 || size < 0 || size > maxListSize) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "分页参数非法：page 从 1 起，size 上限 " + maxListSize);
        }
    }

}
