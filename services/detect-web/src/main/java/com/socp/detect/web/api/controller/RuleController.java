package com.socp.detect.web.api.controller;

import com.socp.detect.web.api.request.RuleSpecRequest;
import com.socp.detect.web.api.request.RuleLookupRequest;
import com.socp.detect.web.config.DetectRuntimeRole;
import com.socp.detect.web.service.DetectEngineService;
import com.socp.detect.web.service.SigmaRuleImporter;
import com.socp.detect.web.persistence.store.DetectionContentCatalog;
import com.socp.detect.web.model.RuleWriteCondition;
import com.socp.platform.auth.security.RequireRole;
import com.socp.platform.error.api.ApiResult;
import com.socp.platform.error.api.PageResponse;
import jakarta.validation.Valid;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Detection rule management API. Continuous event processing is worker-only. */
@RestController
@DetectRuntimeRole(DetectRuntimeRole.Role.API)
@RequestMapping("/api/v1")
public class RuleController {

    static final int MAX_LIST_SIZE = 500;

    private final DetectEngineService engine;

    public RuleController(DetectEngineService engine) {
        this.engine = engine;
    }

    @GetMapping("/rules")
    public ApiResult<?> listRules(@RequestParam(required = false) Integer page,
                                  @RequestParam(required = false) Integer size,
                                  @RequestParam(defaultValue = "") String q,
                                  @RequestParam(defaultValue = "") String status,
                                  @RequestParam(defaultValue = "") String reference,
                                  @RequestParam(defaultValue = "") String referenceAlias) {
        String keyword = bounded(q, 256, "q").trim();
        String lifecycle = bounded(status, 32, "status").trim().toUpperCase(java.util.Locale.ROOT);
        if (!lifecycle.isEmpty() && !List.of("DRAFT", "TESTING", "ACTIVE", "DISABLED", "ARCHIVED").contains(lifecycle)) {
            throw com.socp.platform.error.exception.ApiException.badRequest("unknown rule status");
        }
        bounded(reference, 4096, "reference");
        bounded(referenceAlias, 4096, "referenceAlias");
        boolean filtered = !keyword.isEmpty() || !lifecycle.isEmpty() || !reference.isEmpty() || !referenceAlias.isEmpty();
        int safeSize = size == null || size <= 0 ? 100 : Math.min(MAX_LIST_SIZE, size);
        if (page == null) {
            // Compatibility response for older clients that still omit all
            // pagination parameters. The first bounded page is returned; a
            // tenant-created rule population can no longer cause an unbounded
            // response allocation.
            List<Map<String, Object>> rows = filtered
                    ? engine.searchRules(1, safeSize, keyword, lifecycle, reference, referenceAlias).getContent()
                    : engine.listRules(safeSize);
            return ApiResult.ok(rows == null ? List.of() : rows);
        }
        if (page < 1 || size != null && (size < 1 || size > MAX_LIST_SIZE)) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST,
                    "page must be >= 1 and size must be between 1 and " + MAX_LIST_SIZE);
        }
        var result = filtered ? engine.searchRules(page, safeSize, keyword, lifecycle, reference, referenceAlias)
                : engine.listRulesPage(page, safeSize);
        return ApiResult.ok(PageResponse.of(result.getContent(), result.getTotalElements(),
                result.getNumber() + 1, result.getSize(), result.getTotalPages()));
    }

    public ApiResult<?> listRules(Integer page, Integer size) {
        return listRules(page, size, "", "", "", "");
    }

    @GetMapping("/rules/{id}")
    public ApiResult<Map<String, Object>> getRule(@PathVariable String id, HttpServletResponse response) {
        return ruleResponse(engine.getRule(bounded(id, 128, "id")), response);
    }

    @GetMapping("/rules/options")
    public ApiResult<PageResponse<Map<String, Object>>> ruleOptions(
            @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "50") int size,
            @RequestParam(defaultValue = "") String q) {
        if (page < 1 || size < 1 || size > 100) {
            throw com.socp.platform.error.exception.ApiException.badRequest("page must be >= 1 and size between 1 and 100");
        }
        var result = engine.ruleOptions(page, size, bounded(q, 256, "q").trim());
        return ApiResult.ok(PageResponse.of(result.getContent(), result.getTotalElements(),
                result.getNumber() + 1, result.getSize(), result.getTotalPages()));
    }

    @RequireRole({"admin", "analyst", "viewer"})
    @PostMapping("/rules/lookup")
    public ApiResult<List<Map<String, Object>>> lookupRules(@Valid @RequestBody RuleLookupRequest request) {
        return ApiResult.ok(engine.lookupRules(request.ids().stream().distinct().toList()));
    }

    @GetMapping("/rules/active-techniques")
    public ApiResult<List<String>> activeTechniques() {
        return ApiResult.ok(engine.activeRuleTechniques());
    }

    private static String bounded(String value, int maximum, String field) {
        if (value == null || value.length() > maximum) {
            throw com.socp.platform.error.exception.ApiException.badRequest(field + " exceeds " + maximum + " characters");
        }
        return value;
    }

    /** Source-compatible Java entry point for existing in-process callers. */
    public ApiResult<List<Map<String, Object>>> listRules() {
        List<Map<String, Object>> bounded = engine.listRules(MAX_LIST_SIZE);
        return ApiResult.ok(bounded == null ? List.of() : bounded);
    }

    /** Versioned detection content metadata used by review and release tooling. */
    @GetMapping("/rules/content-manifest")
    public ApiResult<Map<String, Object>> contentManifest() {
        return ApiResult.ok(engine.contentManifest());
    }

    /**
     * Pending "content pack updated but the local rule was customized" records:
     * packaged rules a newer pack version wanted to replace but did not, because
     * the analyst's tuning is authoritative. The pack is never silently applied
     * and never silently dropped.
     */
    @RequireRole({"admin", "analyst"})
    @GetMapping("/rules/content-conflicts")
    public ApiResult<?> contentConflicts(@RequestParam(required = false) Integer page,
                                         @RequestParam(required = false) Integer size) {
        checkHistoryPage(page, size);
        if (page == null) return ApiResult.ok(engine.ruleContentConflicts());
        return historyPage(engine.ruleContentConflictPage(page, size == null ? 20 : size));
    }

    /** Paged metadata, newest first; omitted pagination keeps the bounded legacy array. */
    @RequireRole({"admin", "analyst"})
    @GetMapping("/rules/{id}/revisions")
    public ApiResult<?> ruleRevisions(@PathVariable String id,
                                      @RequestParam(required = false) Integer page,
                                      @RequestParam(required = false) Integer size) {
        bounded(id, 128, "id");
        checkHistoryPage(page, size);
        if (page == null) return ApiResult.ok(engine.listRuleRevisions(id));
        return historyPage(engine.ruleRevisionPage(id, page, size == null ? 20 : size));
    }

    @RequireRole({"admin", "analyst"})
    @GetMapping("/rules/{id}/revisions/{revision}")
    public ApiResult<Map<String, Object>> ruleRevision(@PathVariable String id, @PathVariable long revision) {
        bounded(id, 128, "id");
        if (revision < 1) throw com.socp.platform.error.exception.ApiException.badRequest("revision must be positive");
        return ApiResult.ok(engine.ruleRevision(id, revision));
    }

    private static void checkHistoryPage(Integer page, Integer size) {
        if (page == null && size != null || page != null && page < 1
                || size != null && (size < 1 || size > 100)) {
            throw com.socp.platform.error.exception.ApiException.badRequest(
                    "page must be >= 1 and size between 1 and 100; size requires page");
        }
    }

    private static ApiResult<PageResponse<Map<String, Object>>> historyPage(
            org.springframework.data.domain.Page<Map<String, Object>> result) {
        return ApiResult.ok(PageResponse.of(result.getContent(), result.getTotalElements(),
                result.getNumber() + 1, result.getSize(), result.getTotalPages()));
    }

    /**
     * Rolls a rule back to a historical revision by re-applying that spec as the
     * new head, so the version chain stays append-only. An ACTIVE revision can
     * restore active execution, so this operation requires the same admin role
     * and rule:activate permission as the explicit activation transition.
     */
    @RequireRole("admin")
    @com.socp.platform.auth.security.RequirePermission("rule:activate")
    @PostMapping("/rules/{id}/revisions/{revision}/restore")
    public ApiResult<Map<String, Object>> restoreRuleRevision(@PathVariable String id,
            @PathVariable long revision, @RequestHeader(value = "If-Match", required = false) String ifMatch,
            @RequestHeader(value = "If-None-Match", required = false) String ifNoneMatch, HttpServletResponse response) {
        bounded(id, 128, "id");
        if (revision < 1) throw com.socp.platform.error.exception.ApiException.badRequest("revision must be positive");
        response.setHeader("Cache-Control", "no-store");
        return ruleResponse(engine.restoreRuleRevision(id, revision, RuleWriteCondition.parse(ifMatch, ifNoneMatch, true)), response);
    }

    /**
     * Validate a rule without persisting or hot-reloading it.
     *
     * <p>{@code errors} are what persistence would reject. {@code advisories}
     * never reject: they name a grouping dimension the default routing policy
     * can outrank for a declared data source, which is the pre-flight version of
     * the same partition-locality warning the event path counts as
     * {@code socp_detection_rule_routing_mismatch}. Persistence logs the same
     * advisories on save.</p>
     */
    @RequireRole({"admin", "analyst"})
    @PostMapping("/rules/validate")
    public ApiResult<Map<String, Object>> validateRule(@Valid @RequestBody RuleSpecRequest request) {
        Map<String, Object> enriched = DetectionContentCatalog.enrich(request.asMap());
        List<String> errors = DetectionContentCatalog.validateSpec(enriched);
        List<String> advisories = DetectionContentCatalog.partitionLocalAdvisories(enriched);
        return ApiResult.ok(Map.of("valid", errors.isEmpty(), "errors", errors,
                "advisories", advisories, "spec", enriched));
    }

    @RequireRole({"admin", "analyst"})
    @PostMapping("/rules")
    public ApiResult<Map<String, Object>> addRule(@Valid @RequestBody RuleSpecRequest request, HttpServletResponse response) {
        Map<String, Object> spec = request.asMap();
        rejectDirectActivation(request.status(), request.enabled());
        if (request.status() == null || request.status().isBlank()) {
            // New content starts in the review queue. The UI's legacy
            // enabled toggle must not silently bypass the lifecycle gate.
            spec.put("status", "TESTING");
            spec.put("enabled", false);
        }
        return ruleResponse(engine.addRule(spec), response);
    }

    /** Import a lossless Sigma subset and persist it through the normal rule lifecycle. */
    @RequireRole({"admin", "analyst"})
    @PostMapping(value = "/rules/import/sigma", consumes = {
            MediaType.TEXT_PLAIN_VALUE, "application/yaml", "application/x-yaml"
    })
    public ApiResult<Map<String, Object>> importSigma(@RequestBody String source) {
        SigmaRuleImporter.ImportResult converted = new SigmaRuleImporter().importRule(source);
        Map<String, Object> imported = new LinkedHashMap<>(converted.spec());
        // External content must be reviewed/tested before it can enter the
        // live detector. Promotion is a separate permission-protected call.
        imported.put("status", "TESTING");
        imported.put("enabled", false);
        Map<String, Object> saved = engine.addRule(imported);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("imported", true);
        response.put("ruleId", converted.ruleId());
        response.put("condition", converted.condition());
        response.put("selections", converted.selections());
        response.put("spec", saved);
        return ApiResult.ok(response);
    }

    @RequireRole({"admin", "analyst"})
    @PutMapping("/rules/{id}")
    public ApiResult<Map<String, Object>> updateRule(@PathVariable String id, @Valid @RequestBody RuleSpecRequest request,
            @RequestHeader(value = "If-Match", required = false) String ifMatch, HttpServletResponse response) {
        rejectDirectActivation(request.status(), request.enabled());
        Map<String, Object> spec = request.asMap();
        spec.put("id", bounded(id, 128, "id"));
        response.setHeader("Cache-Control", "no-store");
        return ruleResponse(engine.updateRule(spec, RuleWriteCondition.parse(ifMatch, null, false)), response);
    }

    @RequireRole({"admin", "analyst"})
    @DeleteMapping("/rules/{id}")
    public ApiResult<Map<String, Object>> deleteRule(@PathVariable String id,
            @RequestHeader(value = "If-Match", required = false) String ifMatch, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        return ApiResult.ok(Map.of("removed", engine.deleteRule(bounded(id, 128, "id"), RuleWriteCondition.parse(ifMatch, null, false))));
    }

    /**
     * Submits a ruleset reload for the current tenant.
     *
     * <p>{@code reloaded=true} is a submission receipt, not a claim that every
     * replica is already evaluating the new ruleset: the response says so with
     * {@code effective="async-per-replica"}. Continuous detection runs in the
     * worker role, and each replica drains its in-flight work and rebuilds its
     * own engines independently and asynchronously from the rule-change outbox,
     * so this call cannot observe (or wait for) the other replicas. The API role
     * owns no live engine at all and returns without touching any. Per-replica
     * confirmation is {@code GET /api/v1/stats}, whose {@code ruleStats} and
     * {@code stateRecovery} are replica-local.</p>
     */
    @RequireRole({"admin", "analyst"})
    @PostMapping("/rules/reload")
    public ApiResult<Map<String, Object>> reload() {
        Map<String, Object> response = new LinkedHashMap<>();
        engine.reload();
        response.put("reloaded", true);
        response.put("effective", "async-per-replica");
        response.put("rules", engine.ruleCount());
        return ApiResult.ok(response);
    }

    /** Explicit lifecycle transition; creating or editing a rule never activates it. */
    @RequireRole("admin")
    @com.socp.platform.auth.security.RequirePermission("rule:activate")
    @PostMapping("/rules/{id}/activate")
    public ApiResult<Map<String, Object>> activate(@PathVariable String id,
            @RequestHeader(value = "If-Match", required = false) String ifMatch, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        return ruleResponse(engine.activateRule(bounded(id, 128, "id"), RuleWriteCondition.parse(ifMatch, null, false)), response);
    }

    private static ApiResult<Map<String, Object>> ruleResponse(Map<String, Object> spec, HttpServletResponse response) {
        response.setHeader("ETag", RuleWriteCondition.etag(spec));
        response.setHeader("Cache-Control", "no-store");
        return ApiResult.ok(spec);
    }

    private static void rejectDirectActivation(String status, Boolean enabled) {
        if (status != null && ("ACTIVE".equalsIgnoreCase(status)
                || ("DISABLED".equalsIgnoreCase(status) && Boolean.TRUE.equals(enabled)))) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.FORBIDDEN,
                    "rule activation requires the /activate permission-protected transition");
        }
    }
}
