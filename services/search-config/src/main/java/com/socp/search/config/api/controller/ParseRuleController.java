package com.socp.search.config.api.controller;





import com.socp.search.config.domain.ParseRule;
import com.socp.search.config.api.request.ParseRuleRequest;
import com.socp.search.config.api.request.PreviewRequest;
import com.socp.search.config.service.ParsePreviewService;
import com.socp.search.config.persistence.store.ParseRuleStore;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import com.socp.platform.auth.security.RequireRole;

/**
 * 解析规则 API：CRUD + 实时预览。
 */
@RestController
@RequestMapping("/api/v1/parse-rules")
public class ParseRuleController {

    private final ParseRuleStore store;
    private final ParsePreviewService preview;

    public ParseRuleController(ParseRuleStore store, ParsePreviewService preview) {
        this.store = store;
        this.preview = preview;
    }

    @GetMapping
    public List<ParseRule> list() {
        return store.list();
    }

    @RequireRole({"admin", "analyst"})
    @PostMapping
    public ParseRule create(@Valid @RequestBody ParseRuleRequest rule) {
        return store.save(rule.toDomain());
    }

    @RequireRole({"admin", "analyst"})
    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable String id) {
        return Map.of("removed", store.delete(id));
    }

    /** 预览：用规则 + 示例行验证字段抽取 */
    @RequireRole({"admin", "analyst"})
    @PostMapping("/preview")
    public Map<String, Object> preview(@Valid @RequestBody PreviewRequest req) {
        return preview.preview(req.ruleId(), req.format(), req.pattern(), req.line());
    }

}
