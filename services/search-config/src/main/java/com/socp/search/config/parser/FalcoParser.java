package com.socp.search.config.parser;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Falco 解析器（容器安全运行时告警 JSON）。
 * Falco 事件特征：rule + output + priority + fields{proc.name,user.name,container.id,...}。
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
        if (obj.get("time") != null) out.put("timestamp", String.valueOf(obj.get("time")));
        Object fieldsObj = obj.get("fields");
        if (fieldsObj instanceof Map<?, ?> fm) {
            @SuppressWarnings("unchecked")
            Map<String, Object> f = (Map<String, Object>) fm;
            get(f, "proc.name", v -> out.put(CanonicalEvent.PROCESS_NAME, v));
            get(f, "proc.cmdline", v -> out.put(CanonicalEvent.PROCESS_COMMAND_LINE, v));
            get(f, "proc.pid", v -> out.put(CanonicalEvent.PROCESS_PID, v));
            get(f, "user.name", v -> out.put(CanonicalEvent.USER_NAME, v));
            get(f, "user.uid", v -> out.put(CanonicalEvent.USER_NAME, v));
            get(f, "container.id", v -> out.put("container.id", v));
            get(f, "evt.type", v -> out.put(CanonicalEvent.EVENT_ACTION, String.valueOf(v).toLowerCase()));
            get(f, "fd.name", v -> out.put(CanonicalEvent.FILE_PATH, v));
            get(f, "fd.ip", v -> out.put(CanonicalEvent.DESTINATION_IP, v));
        }
        return out;
    }

    private static void get(Map<String, Object> m, String key, java.util.function.Consumer<String> c) {
        Object v = m.get(key);
        if (v != null) c.accept(String.valueOf(v));
    }

    private static String falcoSeverity(String p) {
        return switch (p.toLowerCase()) {
            case "emergency" -> "CRITICAL";
            case "alert", "critical" -> "CRITICAL";
            case "error" -> "HIGH";
            case "warning" -> "MEDIUM";
            case "notice", "informational" -> "INFO";
            default -> "INFO";
        };
    }
}
