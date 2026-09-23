package com.socp.search.config.parser;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Locale;

/**
 * Falco 解析器（容器安全运行时告警 JSON）。
 * Falco 事件特征：rule + output + priority + output_fields（兼容旧 fields）。
 * 映射到 canonical：event.code(rule) / event.message(output) / event.severity(priority) /
 * process.name / process.command_line / user.name / host.name / container.id（自定义）。
 */
public final class FalcoParser implements EventParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public String name() {
        return "falco";
    }

    @Override
    public Map<String, String> parse(String raw) {
        if (raw == null || !raw.stripLeading().startsWith("{")) {
            return null;
        }
        Map<String, Object> obj;
        try {
            obj = MAPPER.readValue(raw, Map.class);
        } catch (Exception e) {
            return null;
        }
        boolean isFalco = obj.containsKey("rule") && (obj.containsKey("output") || obj.containsKey("priority"));
        if (!isFalco) {
            return null;
        }
        Map<String, String> out = new LinkedHashMap<>();
        out.put("vendor", "falco");
        if (obj.get("rule") != null) out.put(CanonicalEvent.EVENT_CODE, String.valueOf(obj.get("rule")));
        if (obj.get("output") != null) out.put(CanonicalEvent.EVENT_MESSAGE, String.valueOf(obj.get("output")));
        if (obj.get("priority") != null) {
            out.put(CanonicalEvent.EVENT_SEVERITY, falcoSeverity(String.valueOf(obj.get("priority"))));
        }
        if (obj.get("hostname") != null) out.put(CanonicalEvent.HOST_NAME, String.valueOf(obj.get("hostname")));
        get(obj, "timestamp", v -> out.put("timestamp", v));
        get(obj, "ts", v -> out.put("timestamp", v));
        get(obj, "time", v -> out.put("timestamp", v));
        get(obj, "source", v -> out.put("falco.source", v));
        if (obj.get("tags") instanceof java.util.List<?> tags) {
            try { out.put("falco.tags", MAPPER.writeValueAsString(tags)); }
            catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
                throw new IllegalArgumentException("Invalid Falco tags", ex);
            }
        }
        Map<String, Object> f = new LinkedHashMap<>();
        mergeFields(f, obj.get("fields"));
        mergeFields(f, obj.get("output_fields"));
        if (!f.isEmpty()) {
            // One JSON-valued field preserves original scalar types without letting
            // arbitrary vendor keys grow the OpenSearch mapping or become authority.
            try { out.put("falco.output_fields", MAPPER.writeValueAsString(f)); }
            catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
                throw new IllegalArgumentException("Invalid Falco output fields", ex);
            }
            get(f, "proc.name", v -> out.put(CanonicalEvent.PROCESS_NAME, v));
            get(f, "proc.cmdline", v -> out.put(CanonicalEvent.PROCESS_COMMAND_LINE, v));
            get(f, "proc.pid", v -> out.put(CanonicalEvent.PROCESS_PID, v));
            get(f, "user.name", v -> out.put(CanonicalEvent.USER_NAME, v));
            get(f, "user.uid", v -> out.put("user.id", v));
            get(f, "container.id", v -> out.put("container.id", v));
            get(f, "evt.type", v -> out.put(CanonicalEvent.EVENT_ACTION, v.toLowerCase(Locale.ROOT)));
            get(f, "fd.name", v -> out.put(CanonicalEvent.FILE_PATH, v));
            get(f, "fd.ip", v -> out.put(CanonicalEvent.DESTINATION_IP, v));
        }
        get(obj, "proc", v -> out.putIfAbsent(CanonicalEvent.PROCESS_NAME, v));
        get(obj, "cmdline", v -> out.putIfAbsent(CanonicalEvent.PROCESS_COMMAND_LINE, v));
        return out;
    }

    private static void mergeFields(Map<String, Object> target, Object value) {
        if (value instanceof Map<?, ?> fields) {
            fields.forEach((key, item) -> {
                if (key instanceof String text) target.put(text, item);
            });
        }
    }

    private static void get(Map<String, Object> m, String key, java.util.function.Consumer<String> c) {
        Object v = m.get(key);
        if (v != null) c.accept(String.valueOf(v));
    }

    private static String falcoSeverity(String p) {
        return switch (p.toLowerCase(Locale.ROOT)) {
            case "emergency" -> "CRITICAL";
            case "alert", "critical" -> "CRITICAL";
            case "error" -> "HIGH";
            case "warning" -> "MEDIUM";
            case "notice", "informational" -> "INFO";
            default -> "INFO";
        };
    }
}
