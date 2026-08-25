package com.socp.search.config.api;

import com.socp.search.config.domain.ParseRule;
import com.socp.search.config.service.ParsePreviewService;
import com.socp.search.config.store.ParseRuleStore;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import com.socp.platform.auth.RequireRole;

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

    public record PreviewRequest(
            @NotBlank @Size(max = 128) String ruleId,
            @NotBlank @Size(max = 32) String format,
            @Size(max = 65536) String pattern,
            @Size(max = 65536) String line) {
    }
}
