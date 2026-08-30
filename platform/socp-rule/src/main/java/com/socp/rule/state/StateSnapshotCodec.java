package com.socp.rule.state;

import com.socp.rule.model.SecurityEvent;
import com.socp.rule.model.Severity;
import com.socp.rule.util.Json;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** Small JSON codec shared by stateful rules; event timestamps stay portable strings. */
public final class StateSnapshotCodec {

    private StateSnapshotCodec() {
    }

    public static byte[] write(Object value) {
        try {
            return Json.mapper().writeValueAsBytes(value);
        } catch (Exception failure) {
            throw new IllegalStateException("unable to encode rule state snapshot", failure);
        }
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> read(byte[] value) {
        if (value == null || value.length == 0) return Map.of();
        try {
            Object decoded = Json.mapper().readValue(value, Object.class);
            return decoded instanceof Map<?, ?> map
                    ? (Map<String, Object>) map : Map.of();
        } catch (Exception failure) {
            throw new IllegalArgumentException("invalid rule state snapshot", failure);
        }
    }

    public static Map<String, Object> event(SecurityEvent event) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (event == null) return out;
        out.put("id", event.id());
        out.put("timestamp", event.timestamp() == null ? null : event.timestamp().toString());
        out.put("source", event.source());
        out.put("host", event.host());
        out.put("raw", event.raw());
        out.put("fields", event.fields() == null ? Map.of() : event.fields());
        out.put("severity", event.severity() == null ? Severity.INFO.name() : event.severity().name());
        return out;
    }

    @SuppressWarnings("unchecked")
    public static SecurityEvent event(Object value) {
        if (!(value instanceof Map<?, ?> raw)) return null;
        Map<String, Object> map = (Map<String, Object>) raw;
        Instant timestamp;
        try {
            timestamp = Instant.parse(String.valueOf(map.getOrDefault("timestamp", Instant.EPOCH)));
        } catch (Exception ignored) {
            timestamp = Instant.EPOCH;
        }
        Map<String, String> fields = new LinkedHashMap<>();
        Object rawFields = map.get("fields");
        if (rawFields instanceof Map<?, ?> values) {
            values.forEach((key, item) -> fields.put(String.valueOf(key), String.valueOf(item)));
        }
        Severity severity;
        try {
            severity = Severity.valueOf(String.valueOf(map.getOrDefault("severity", "INFO")));
        } catch (Exception ignored) {
            severity = Severity.INFO;
        }
        return new SecurityEvent(String.valueOf(map.getOrDefault("id", "snapshot-event")), timestamp,
                String.valueOf(map.getOrDefault("source", "unknown")),
                String.valueOf(map.getOrDefault("host", "unknown")),
                String.valueOf(map.getOrDefault("raw", "")), fields, severity);
    }
}
