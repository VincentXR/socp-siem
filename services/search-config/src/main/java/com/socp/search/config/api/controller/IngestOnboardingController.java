package com.socp.search.config.api.controller;

import com.socp.platform.auth.security.RequireRole;
import com.socp.platform.error.api.ApiResult;
import com.socp.platform.error.exception.ApiException;
import com.socp.search.config.api.response.SinkTargetView;
import com.socp.search.config.config.SearchRuntimeRole;
import com.socp.search.config.domain.LogSource;
import com.socp.search.config.persistence.store.LogSourceStore;
import com.socp.search.config.persistence.store.ParseRuleStore;
import com.socp.search.config.persistence.store.SinkTargetStore;
import com.socp.search.config.render.VectorConfigRenderer;
import com.socp.search.config.service.IngestPreviewService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Source-specific onboarding is configuration inspection, never remote collector control. */
@RestController
@SearchRuntimeRole(SearchRuntimeRole.Role.API)
@RequestMapping("/api/v1/sources")
public class IngestOnboardingController {
    private final LogSourceStore sources;
    private final SinkTargetStore outputs;
    private final ParseRuleStore rules;
    private final IngestPreviewService previews;
    public IngestOnboardingController(LogSourceStore sources, SinkTargetStore outputs,
                                      ParseRuleStore rules, IngestPreviewService previews) {
        this.sources = sources; this.outputs = outputs; this.rules = rules; this.previews = previews;
    }
    public record Sample(@NotBlank @Size(max = 65536) String sample) { }

    @RequireRole({"admin", "analyst"})
    @PostMapping("/{id}/preview")
    public ApiResult<Map<String, Object>> preview(@PathVariable String id, @Valid @RequestBody Sample sample) {
        return ApiResult.ok(previews.preview(require(id), sample.sample()));
    }

    @RequireRole({"admin", "analyst"})
    @GetMapping("/{id}/setup")
    public ApiResult<Map<String, Object>> setup(@PathVariable String id) {
        LogSource source = require(id);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("source", source);
        result.put("collectorTag", source.collectorTag());
        result.put("nativeVector", VectorConfigRenderer.isVectorNative(source));
        result.put("appliedState", "UNKNOWN");
        var output = outputs.resolveForRendering(source.sinkTargetId());
        result.put("output", output == null ? null : SinkTargetView.of(output));
        List<String> problems = new ArrayList<>();
        try { VectorConfigRenderer.requireReady(source); }
        catch (ApiException missing) { problems.add(missing.getMessage()); }
        if (!source.enabled()) problems.add("Source is a disabled draft; enable and manually apply before collecting");
        if (output == null || !output.enabled() || output.uri() == null || output.uri().isBlank()) {
            problems.add("No enabled effective output: bind an output or configure SOCP_VECTOR_URI");
        }
        if (!VectorConfigRenderer.isVectorNative(source)) {
            problems.add("External managed collector required; automatic connector deployment is unsupported");
        }
        if (output != null && output.enabled()) {
            try { new com.socp.search.config.api.request.SinkTargetRequest(output.name(), output.type(),
                    output.uri(), null, true).validateHttpOutput(); }
            catch (ApiException invalid) { problems.add(invalid.getMessage()); }
        }
        result.put("problems", problems);
        List<Map<String, Object>> pipeline = new ArrayList<>();
        StringBuilder material = new StringBuilder(source.toString());
        if (output != null) {
            // Platform metadata creation time is replica-local, not saved configuration.
            // Credentials remain outside this public, non-secret configuration fingerprint.
            material.append(output.id()).append(output.name()).append(output.type())
                    .append(output.uri()).append(output.enabled())
                    .append(output.authToken() != null && !output.authToken().isBlank());
        }
        boolean explicit = !source.parseRuleIds().isEmpty();
        List<String> candidates = explicit ? source.parseRuleIds() : rules.enabled().stream()
                .filter(rule -> scopeMatches(rule, source.id()))
                .sorted(java.util.Comparator.comparingInt(com.socp.search.config.domain.ParseRule::order))
                .map(com.socp.search.config.domain.ParseRule::id).toList();
        for (String ruleId : candidates) {
            var rule = rules.get(ruleId);
            material.append(rule);
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", ruleId);
            entry.put("name", rule == null ? ruleId : rule.name());
            entry.put("enabled", rule != null && rule.enabled());
            entry.put("scopeMatches", rule != null && scopeMatches(rule, source.id()));
            entry.put("exists", rule != null);
            pipeline.add(entry);
        }
        result.put("pipeline", pipeline);
        result.put("configurationVersion", fingerprint(material.toString()));
        result.put("pipelineMode", explicit ? "FIRST_MATCH_IN_BINDING_ORDER" : "BUILTIN_THEN_SPARSE_FALLBACK");
        return ApiResult.ok(result);
    }
    private static boolean scopeMatches(com.socp.search.config.domain.ParseRule rule, String sourceId) {
        return rule.sourceId() == null || rule.sourceId().isBlank() || sourceId.equals(rule.sourceId());
    }
    private static String fingerprint(String material) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(material.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }
    private LogSource require(String id) {
        return sources.get(id).orElseThrow(() -> ApiException.notFound("Log source not found: " + id));
    }
}
