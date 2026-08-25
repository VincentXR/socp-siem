package com.socp.detect.web.api.request;
import com.socp.rule.model.SecurityEvent;
import com.socp.rule.model.Severity;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Typed HTTP contract for the local Detection ingest endpoint. */
public record DetectionIngestRequest(
        @Size(max = 128) String eventId,
        @Size(max = 64) String timestamp,
        @Size(max = 128) String source,
        @Size(max = 256) String host,
        @Pattern(regexp = "(?i)CRITICAL|HIGH|MEDIUM|LOW|INFO") @Size(max = 32) String severity,
        @Size(max = 4096) String msg,
        @Size(max = 65536) String raw,
        @Size(max = 128) Map<@Size(max = 128) String, @Size(max = 4096) String> fields) {

    public SecurityEvent toSecurityEvent(String tenantId) {
        Map<String, String> normalizedFields = new LinkedHashMap<>();
        if (fields != null) normalizedFields.putAll(fields);
        if (tenantId != null && !tenantId.isBlank() && !tenantId.equals(normalizedFields.get("tenant_id"))) {
            normalizedFields.put("tenant_id", tenantId);
        }
        if (msg != null && !normalizedFields.containsKey("msg")) normalizedFields.put("msg", msg);

        return new SecurityEvent(normalizeEventId(eventId), parseTimestamp(timestamp),
                fallback(source, "unknown"), fallback(host, "unknown"),
                raw == null ? fallback(msg, "") : raw, normalizedFields, parseSeverity(severity));
    }

    private static String normalizeEventId(String value) {
        if (value == null || value.isBlank() || "null".equalsIgnoreCase(value)) {
            return UUID.randomUUID().toString();
        }
        return value.trim();
    }

    private static Instant parseTimestamp(String value) {
        if (value == null || value.isBlank()) return Instant.now();
        try {
            return Instant.parse(value);
        } catch (Exception ignored) {
            return Instant.now();
        }
    }

    private static Severity parseSeverity(String value) {
        if (value == null || value.isBlank()) return Severity.INFO;
        try {
            return Severity.valueOf(value.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return Severity.INFO;
        }
    }

    private static String fallback(String value, String defaultValue) {
        return value == null ? defaultValue : value;
    }
}
