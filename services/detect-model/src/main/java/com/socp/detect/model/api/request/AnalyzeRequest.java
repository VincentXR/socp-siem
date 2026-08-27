package com.socp.detect.model.api.request;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.LinkedHashMap;
import java.util.Map;

/** Bounded HTTP contract for direct secondary alert analysis. */
public record AnalyzeRequest(
        @Size(max = 128) String tenantId,
        @Size(max = 128) String tenant_id,
        @Size(max = 128) String sourceAlarmId,
        @Size(max = 128) String source_alarm_id,
        @Size(max = 128) String analyzerVersion,
        @Size(max = 128) String ruleId,
        @Size(max = 256) String entity,
        @Size(max = 4096) String message,
        @Pattern(regexp = "(?i)CRITICAL|HIGH|MEDIUM|LOW|INFO") @Size(max = 32) String severity,
        @Size(max = 128) String source) {

    public Map<String, Object> asMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        put(out, "tenantId", tenantId);
        put(out, "tenant_id", tenant_id);
        put(out, "sourceAlarmId", sourceAlarmId);
        put(out, "source_alarm_id", source_alarm_id);
        put(out, "analyzerVersion", analyzerVersion);
        put(out, "ruleId", ruleId);
        put(out, "entity", entity);
        put(out, "message", message);
        put(out, "severity", severity);
        put(out, "source", source);
        return out;
    }

    private static void put(Map<String, Object> out, String key, String value) {
        if (value != null && !value.isBlank()) out.put(key, value);
    }
}
