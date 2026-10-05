package com.socp.platform.audit.aspect;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.socp.platform.audit.model.AuditRecord;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Bounded command summary: only explicit non-secret state fields carry values. */
final class AuditDetails {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> SAFE = Set.of("status", "assignee", "enabled", "classification",
            "operation", "alarmId", "targetCaseId", "expectedVersion", "targetExpectedVersion");

    static AuditRecord capture(Method method, Object[] args, Object result, AuditRecord record) {
        String id = null;
        Map<String, Object> requested = new LinkedHashMap<>();
        java.util.Set<String> fieldsChanged = new java.util.TreeSet<>();
        var parameters = method.getParameters();
        for (int i = 0; args != null && i < Math.min(args.length, parameters.length); i++) {
            Object arg = args[i];
            var path = parameters[i].getAnnotation(PathVariable.class);
            if (path != null && arg != null && scalar(arg)) id = bounded(String.valueOf(arg), 512);
            var query = parameters[i].getAnnotation(RequestParam.class);
            String name = query != null && !query.value().isBlank() ? query.value()
                    : query != null && !query.name().isBlank() ? query.name() : parameters[i].getName();
            captureField(requested, name, arg);
            if (arg instanceof Map<?, ?> fields) {
                fields.forEach((key, value) -> {
                    captureField(requested, String.valueOf(key), value);
                    if (fieldsChanged.size() < 32 && String.valueOf(key).matches("[a-zA-Z][a-zA-Z0-9_]{0,63}")) fieldsChanged.add(String.valueOf(key));
                });
                if (id == null && fields.get("id") != null) id = bounded(String.valueOf(fields.get("id")), 512);
            } else if (arg != null && arg.getClass().isRecord()) {
                for (var field : arg.getClass().getRecordComponents()) {
                    try {
                        Object value = field.getAccessor().invoke(arg);
                        captureField(requested, field.getName(), value);
                        if (value != null && fieldsChanged.size() < 32) fieldsChanged.add(field.getName());
                    }
                    catch (ReflectiveOperationException ignored) { /* Never serialize arbitrary object graphs. */ }
                }
            }
        }
        if (id == null) id = resultId(result, 0);
        String summary;
        try { summary = JSON.writeValueAsString(Map.of("requestedChanges", requested, "fields", fieldsChanged)); }
        catch (Exception ignored) { summary = "{}"; }
        return record.withDetails(id, bounded(summary, 4096), bounded(org.slf4j.MDC.get("traceId"), 128));
    }

    private static void captureField(Map<String, Object> fields, String name, Object value) {
        if (value != null && SAFE.contains(name) && scalar(value)) {
            fields.put(name, value instanceof String text ? bounded(text, 255) : value);
        }
    }

    private static String resultId(Object value, int depth) {
        if (value == null || depth > 3) return null;
        if (value instanceof Map<?, ?> map) {
            for (String identity : new String[]{"id", "caseId", "alarmId", "ruleId"}) {
                Object id = map.get(identity);
                if (id != null && scalar(id)) return bounded(String.valueOf(id), 512);
            }
            for (String key : new String[]{"data", "case", "alarm"}) {
                String nested = resultId(map.get(key), depth + 1);
                if (nested != null) return nested;
            }
        } else {
            for (String getter : new String[]{"getId", "id", "data", "getData"}) {
                try {
                    Object candidate = value.getClass().getMethod(getter).invoke(value);
                    if (getter.equals("getId") || getter.equals("id")) {
                        if (candidate != null && scalar(candidate)) return bounded(String.valueOf(candidate), 512);
                    } else {
                        String nested = resultId(candidate, depth + 1);
                        if (nested != null) return nested;
                    }
                } catch (ReflectiveOperationException ignored) { }
            }
        }
        return null;
    }

    private static boolean scalar(Object value) {
        return value instanceof String || value instanceof Number || value instanceof Boolean || value instanceof Enum<?>;
    }
    private static String bounded(String value, int max) {
        return value == null ? null : value.substring(0, Math.min(max, value.length()));
    }
    private AuditDetails() { }
}
