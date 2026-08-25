package com.socp.incident.web.api.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.LinkedHashMap;
import java.util.Map;

public record AlarmRequest(
        @NotBlank @Size(max = 128) String id,
        @Size(max = 128) String ruleId,
        @Size(max = 256) String ruleName,
        @Pattern(regexp = "CRITICAL|HIGH|MEDIUM|LOW|INFO") @Size(max = 32) String severity,
        @Size(max = 256) String entity,
        @Size(max = 4096) String message,
        @Size(max = 128) String mitre,
        @Size(max = 64) String occurredAt) {

    public Map<String, Object> asMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        put(out, "id", id); put(out, "ruleId", ruleId); put(out, "ruleName", ruleName);
        put(out, "severity", severity); put(out, "entity", entity); put(out, "message", message);
        put(out, "mitre", mitre); put(out, "occurredAt", occurredAt);
        return out;
    }

    private static void put(Map<String, Object> out, String key, String value) {
        if (value != null && !value.isBlank()) out.put(key, value);
    }
}
