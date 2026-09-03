package com.socp.detect.web.api.request;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Typed HTTP contract for the supported detection-rule DSL. */
public record RuleSpecRequest(
        @Size(max = 128) String id,
        @NotBlank @Size(max = 256) String name,
        @NotBlank @Pattern(regexp = "(?i)pattern|threshold|correlation|correlation-set|baseline|rare") @Size(max = 32) String type,
        @NotBlank @Pattern(regexp = "(?i)CRITICAL|HIGH|MEDIUM|LOW|INFO") @Size(max = 32) String severity,
        @Size(max = 4096) String message,
        Boolean enabled,
        @Size(max = 64) String window,
        @Size(max = 128) String keyField,
        @Size(max = 128) String routingField,
        @Positive Integer threshold,
        @Size(max = 128) String valueField,
        @Positive Integer warmup,
        @Positive Integer baselineWindows,
        @DecimalMin("0.0") @DecimalMax("100.0") Double sigma,
        @Positive Integer minCount,
        @Size(max = 128) String mitre,
        @Size(max = 64) String version,
        @Pattern(regexp = "(?i)DRAFT|TESTING|ACTIVE|DISABLED|ARCHIVED") @Size(max = 32) String status,
        @Size(max = 128) String owner,
        @Size(max = 128) String contentPack,
        @Size(max = 64) String contentVersion,
        @Size(max = 128) List<@Valid RuleConditionRequest> match,
        @Size(max = 128) List<@Size(max = 128) @Valid List<@Valid RuleConditionRequest>> matchAny,
        @Size(max = 64) List<@Size(max = 128) @Valid List<@Valid RuleConditionRequest>> steps,
        @Valid AlertTemplateRequest alert,
        @Size(max = 128) List<@Valid RuleConditionRequest> whitelist) {

    public Map<String, Object> asMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        put(out, "id", id); put(out, "name", name); put(out, "type", type); put(out, "severity", severity);
        put(out, "message", message); put(out, "enabled", enabled); put(out, "window", window);
        put(out, "keyField", keyField); put(out, "routingField", routingField); put(out, "threshold", threshold);
        put(out, "valueField", valueField); put(out, "warmup", warmup); put(out, "baselineWindows", baselineWindows);
        put(out, "sigma", sigma); put(out, "minCount", minCount); put(out, "mitre", mitre);
        put(out, "version", version); put(out, "status", status); put(out, "owner", owner);
        put(out, "contentPack", contentPack); put(out, "contentVersion", contentVersion);
        if (alert != null) {
            Map<String, Object> template = new LinkedHashMap<>();
            put(template, "title", alert.title());
            put(template, "description", alert.description());
            if (!template.isEmpty()) out.put("alert", template);
        }
        if (match != null) out.put("match", match.stream().map(RuleConditionRequest::asMap).toList());
        if (matchAny != null) out.put("matchAny", matchAny.stream()
                .map(group -> group.stream().map(RuleConditionRequest::asMap).toList()).toList());
        if (steps != null) out.put("steps", steps.stream()
                .map(step -> step.stream().map(RuleConditionRequest::asMap).toList()).toList());
        if (whitelist != null) out.put("whitelist", whitelist.stream()
                .map(RuleConditionRequest::asMap).toList());
        return out;
    }

    private static void put(Map<String, Object> out, String key, Object value) {
        if (value != null) out.put(key, value);
    }

    public record AlertTemplateRequest(
            @Size(max = 512) String title,
            @Size(max = 4096) String description) {
    }

    public record RuleConditionRequest(
            @NotBlank @Size(max = 128) String field,
            @NotBlank @Size(max = 32) String op,
            @NotBlank @Size(max = 4096) String value) {

        Map<String, Object> asMap() {
            return Map.of("field", field, "op", op, "value", value);
        }
    }
}
