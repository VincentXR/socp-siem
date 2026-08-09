package com.socp.search.config.api;

import com.socp.search.config.domain.ParseRule;
import com.socp.search.config.service.ParsePreviewService;
import com.socp.search.config.store.ParseRuleStore;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

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

    @PostMapping
    public ParseRule create(@RequestBody ParseRule rule) {
        return store.save(rule);
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable String id) {
        return Map.of("removed", store.delete(id));
    }

    /** 预览：用规则 + 示例行验证字段抽取 */
    @PostMapping("/preview")
    public Map<String, Object> preview(@RequestBody PreviewRequest req) {
        return preview.preview(req.ruleId(), req.format(), req.pattern(), req.line());
    }

    public record PreviewRequest(String ruleId, String format, String pattern, String line) {
    }
}
