package com.socp.search.config.api.controller;


import com.socp.search.config.domain.ParseRule;
import com.socp.search.config.api.request.ParseRuleRequest;
import com.socp.search.config.api.request.PreviewRequest;
import com.socp.search.config.service.ParsePreviewService;
import com.socp.search.config.service.ParseRuleExecutor;
import com.socp.search.config.parser.ParserRegistry;
import com.socp.search.config.persistence.store.ParseRuleStore;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
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

/**
 * 解析规则 API：CRUD + 实时预览。
 */
@RestController
@RequestMapping("/api/v1/parse-rules")
public class ParseRuleController {

    private final ParseRuleStore store;
    private final ParsePreviewService preview;
    private final ParseRuleExecutor executor;

    @Autowired
    public ParseRuleController(ParseRuleStore store, ParsePreviewService preview,
                               ParseRuleExecutor executor) {
        this.store = store;
        this.preview = preview;
        this.executor = executor;
    }

    /** Source-compatible constructor for lightweight controller tests. */
    public ParseRuleController(ParseRuleStore store, ParsePreviewService preview) {
        this(store, preview, new ParseRuleExecutor(new ParserRegistry()));
    }

    @GetMapping
    public List<ParseRule> list() {
        return store.list();
    }

    @RequireRole({"admin", "analyst"})
    @PostMapping
    public ParseRule create(@Valid @RequestBody ParseRuleRequest rule) {
        ParseRule domain = rule.toDomain();
        try {
            executor.compile(domain);
        } catch (IllegalArgumentException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "invalid parse rule: " + invalid.getMessage(), invalid);
        }
        return store.save(domain);
    }

    @RequireRole({"admin", "analyst"})
    @org.springframework.web.bind.annotation.PutMapping("/{id}")
    public ParseRule update(@PathVariable String id, @Valid @RequestBody ParseRuleRequest request) {
        ParseRule existing = store.get(id);
        if (existing == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Parse rule not found");
        ParseRule draft = request.toDomain();
        ParseRule updated = new ParseRule(existing.id(), draft.name(), draft.sourceId(), draft.format(),
                draft.pattern(), draft.mapping(), draft.setFields(), draft.filters(), draft.enabled(),
                draft.order(), existing.createdAt());
        try { executor.compile(updated); }
        catch (IllegalArgumentException invalid) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, invalid.getMessage(), invalid); }
        return store.save(updated);
    }

    public record DraftPreview(@Valid @jakarta.validation.constraints.NotNull ParseRuleRequest rule,
            @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max = 1048576) String line) { }

    @RequireRole({"admin", "analyst"})
    @PostMapping("/preview-draft")
    public Map<String, Object> previewDraft(@Valid @RequestBody DraftPreview request) {
        try {
            var result = executor.execute(request.rule().toDomain(), request.line());
            Map<String, Object> response = new java.util.LinkedHashMap<>();
            response.put("matched", result.matched());
            response.put("fields", result.fields());
            if (result.error() != null) response.put("error", result.error());
            return response;
        } catch (IllegalArgumentException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, invalid.getMessage(), invalid);
        }
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
