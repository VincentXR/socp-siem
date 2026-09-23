package com.socp.search.config.api.controller;


import com.socp.search.config.domain.ParseRule;
import com.socp.search.config.api.request.ParseRuleRequest;
import com.socp.search.config.api.request.PreviewRequest;
import com.socp.search.config.service.ParsePreviewService;
import com.socp.search.config.service.ParseRuleExecutor;
import com.socp.search.config.parser.ParserRegistry;
import com.socp.search.config.persistence.store.ParseRuleStore;
import com.socp.platform.error.api.ApiResult;
import com.socp.platform.error.api.PageResponse;
import com.socp.platform.error.exception.ApiException;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;
import java.util.Map;
import com.socp.platform.auth.security.RequireRole;

/**
 * 解析规则 API：CRUD + 实时预览。
 */
@RestController
@com.socp.search.config.config.SearchRuntimeRole(
        com.socp.search.config.config.SearchRuntimeRole.Role.API)
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
    public ApiResult<?> list(@RequestParam(required = false) Integer page,
                             @RequestParam(required = false) Integer size,
                             @RequestParam(required = false) String q) {
        int safeSize = size == null || size <= 0 ? 100 : Math.min(500, size);
        String query = q == null ? "" : q.trim();
        if (query.length() > 128) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "q must be at most 128 characters");
        }
        if (page == null) return ApiResult.ok(store.page(query, PageRequest.of(0, 500)).getContent());
        if (page < 1 || size != null && (size < 1 || size > 500)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "page must be >= 1 and size must be between 1 and 500");
        }
        var result = store.page(query, PageRequest.of(page - 1, safeSize));
        return ApiResult.ok(PageResponse.of(result.getContent(), result.getTotalElements(),
                page, safeSize, result.getTotalPages()));
    }

    /** Source-compatible Java entry point for direct callers. */
    public ApiResult<List<ParseRule>> list() {
        return ApiResult.ok(store.page("", PageRequest.of(0, 500)).getContent());
    }

    @GetMapping("/{id}")
    public ApiResult<ParseRule> get(@PathVariable String id) {
        ParseRule rule = store.get(id);
        if (rule == null) throw ApiException.notFound("Parse rule not found");
        return ApiResult.ok(rule);
    }

    @GetMapping("/batch/resolve")
    public ApiResult<List<ParseRule>> resolve(@RequestParam List<String> ids) {
        if (ids == null || ids.isEmpty() || ids.size() > 100
                || ids.stream().anyMatch(id -> id == null || id.isBlank() || id.length() > 255)
                || String.join(",", ids).length() > 6000) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "ids must contain 1-100 rule IDs within 6000 characters");
        }
        return ApiResult.ok(store.getMany(ids.stream().distinct().toList()));
    }

    @RequireRole({"admin", "analyst"})
    @PostMapping
    public ApiResult<ParseRule> create(@Valid @RequestBody ParseRuleRequest rule) {
        ParseRule domain = rule.toDomain();
        try {
            executor.compile(domain);
        } catch (IllegalArgumentException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "invalid parse rule: " + invalid.getMessage(), invalid);
        }
        return ApiResult.ok(store.create(domain));
    }

    @RequireRole({"admin", "analyst"})
    @org.springframework.web.bind.annotation.PutMapping("/{id}")
    public ApiResult<ParseRule> update(@PathVariable String id, @Valid @RequestBody ParseRuleRequest request) {
        ParseRule draft = request.toDomain();
        return ApiResult.ok(store.update(id, existing -> {
            ParseRule updated = new ParseRule(existing.id(), draft.name(), draft.sourceId(),
                    draft.format(), draft.pattern(), draft.mapping(), draft.setFields(),
                    draft.filters(), draft.enabled(), draft.order(), existing.createdAt());
            try { executor.compile(updated); }
            catch (IllegalArgumentException invalid) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, invalid.getMessage(), invalid);
            }
            return updated;
        }));
    }

    public record DraftPreview(@Valid @jakarta.validation.constraints.NotNull ParseRuleRequest rule,
            @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max = 1048576) String line) { }

    @RequireRole({"admin", "analyst"})
    @PostMapping("/preview-draft")
    public ApiResult<Map<String, Object>> previewDraft(@Valid @RequestBody DraftPreview request) {
        try {
            var result = executor.execute(request.rule().toDomain(), request.line());
            Map<String, Object> response = new java.util.LinkedHashMap<>();
            response.put("matched", result.matched());
            response.put("fields", result.fields());
            if (result.error() != null) response.put("error", result.error());
            return ApiResult.ok(response);
        } catch (IllegalArgumentException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, invalid.getMessage(), invalid);
        }
    }

    @RequireRole({"admin", "analyst"})
    @DeleteMapping("/{id}")
    public ApiResult<Map<String, Object>> delete(@PathVariable String id) {
        return ApiResult.ok(Map.of("removed", store.delete(id)));
    }

    /** 预览：用规则 + 示例行验证字段抽取 */
    @RequireRole({"admin", "analyst"})
    @PostMapping("/preview")
    public ApiResult<Map<String, Object>> preview(@Valid @RequestBody PreviewRequest req) {
        return ApiResult.ok(preview.preview(req.ruleId(), req.format(), req.pattern(), req.line()));
    }

}
