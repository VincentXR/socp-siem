package com.socp.soar.web.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Shared read-model/audit redaction policy; provider values must never enter these payloads. */
final class SoarRedaction {
    private SoarRedaction() { }
    static Object structured(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> output = new LinkedHashMap<>();
            map.forEach((key, item) -> {
                String name = String.valueOf(key).toLowerCase(Locale.ROOT);
                output.put(String.valueOf(key), name.contains("secret") || name.contains("token")
                        || name.contains("password") || name.contains("authorization") || name.equals("cookie") || name.equals("api_key") || name.equals("apikey") || name.equals("api-key")
                        ? "[REDACTED]" : structured(item));
            }); return output;
        }
        if (value instanceof List<?> list) return list.stream().map(SoarRedaction::structured).toList();
        return value;
    }

    /** Redact credential-shaped material even when an operator pasted it into
     * free-text evidence/reason rather than a structured JSON field. */
    static String freeText(String value, int max) {
        if (value == null) return "";
        String safe = value.replaceAll("(?i)(bearer\\s+)[^\\s,;]+", "$1[REDACTED]")
                .replaceAll("(?i)((?:secret|token|password|authorization|api[_-]?key)\\s*[:=]\\s*)[^\\s,;]+",
                        "$1[REDACTED]");
        return safe.length() <= max ? safe : safe.substring(0, max);
    }

}
