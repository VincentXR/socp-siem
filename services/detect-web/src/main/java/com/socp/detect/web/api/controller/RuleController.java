package com.socp.detect.web.api.controller;

import com.socp.detect.web.api.request.RuleSpecRequest;
import com.socp.detect.web.config.DetectRuntimeRole;
import com.socp.detect.web.service.DetectEngineService;
import com.socp.detect.web.service.SigmaRuleImporter;
import com.socp.detect.web.persistence.store.DetectionContentCatalog;
import com.socp.platform.auth.security.RequireRole;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Detection rule management API. Continuous event processing is worker-only. */
@RestController
@DetectRuntimeRole(DetectRuntimeRole.Role.API)
@RequestMapping("/api/v1")
public class RuleController {

    private final DetectEngineService engine;

    public RuleController(DetectEngineService engine) {
        this.engine = engine;
    }

    @GetMapping("/rules")
    public List<Map<String, Object>> listRules() {
        return engine.listRules();
    }

    /** Versioned detection content metadata used by review and release tooling. */
    @GetMapping("/rules/content-manifest")
    public Map<String, Object> contentManifest() {
        return engine.contentManifest();
    }

    /** Validate a rule without persisting or hot-reloading it. */
    @RequireRole({"admin", "analyst"})
    @PostMapping("/rules/validate")
    public Map<String, Object> validateRule(@Valid @RequestBody RuleSpecRequest request) {
        Map<String, Object> enriched = DetectionContentCatalog.enrich(request.asMap());
        List<String> errors = DetectionContentCatalog.validateSpec(enriched);
        return Map.of("valid", errors.isEmpty(), "errors", errors, "spec", enriched);
    }

    @RequireRole({"admin", "analyst"})
    @PostMapping("/rules")
    public Map<String, Object> addRule(@Valid @RequestBody RuleSpecRequest request) {
        Map<String, Object> spec = request.asMap();
        rejectDirectActivation(request.status(), request.enabled());
        if (request.status() == null || request.status().isBlank()) {
            // New content starts in the review queue. The UI's legacy
            // enabled toggle must not silently bypass the lifecycle gate.
            spec.put("status", "TESTING");
            spec.put("enabled", false);
        }
        return engine.addRule(spec);
    }

    /** Import a lossless Sigma subset and persist it through the normal rule lifecycle. */
    @RequireRole({"admin", "analyst"})
    @PostMapping(value = "/rules/import/sigma", consumes = {
            MediaType.TEXT_PLAIN_VALUE, "application/yaml", "application/x-yaml"
    })
    public Map<String, Object> importSigma(@RequestBody String source) {
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
        return response;
    }

    @RequireRole({"admin", "analyst"})
    @PutMapping("/rules/{id}")
    public Map<String, Object> updateRule(@PathVariable String id, @Valid @RequestBody RuleSpecRequest request) {
        rejectDirectActivation(request.status(), request.enabled());
        Map<String, Object> spec = request.asMap();
        spec.put("id", id);
        return engine.updateRule(spec);
    }

    @RequireRole({"admin", "analyst"})
    @DeleteMapping("/rules/{id}")
    public Map<String, Object> deleteRule(@PathVariable String id) {
        return Map.of("removed", engine.deleteRule(id));
    }

    @RequireRole({"admin", "analyst"})
    @PostMapping("/rules/reload")
    public Map<String, Object> reload() {
        Map<String, Object> response = new LinkedHashMap<>();
        engine.reload();
        response.put("reloaded", true);
        response.put("rules", engine.listRules().size());
        return response;
    }

    /** Explicit lifecycle transition; creating or editing a rule never activates it. */
    @RequireRole("admin")
    @com.socp.platform.auth.security.RequirePermission("rule:activate")
    @PostMapping("/rules/{id}/activate")
    public Map<String, Object> activate(@PathVariable String id) {
        return engine.activateRule(id);
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
