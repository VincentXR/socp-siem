package com.socp.soar.web.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.socp.soar.web.definition.SoarExpressionEngine;
import com.socp.soar.web.persistence.entity.SoarAutomationRuleEntity;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Pure event-boundary and condition-matching logic for automation rules.
 *
 * <p>The rule service owns durable admission and receipt state; this
 * collaborator owns the untrusted event shape and deterministic predicates so
 * that persistence code cannot accidentally become an input parser.</p>
 */
final class SoarAutomationRuleMatcher {

    private final ObjectMapper mapper;

    SoarAutomationRuleMatcher(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    Map<String, Object> normalizeEvent(Map<String, Object> event, String tenant) {
        if (event == null || event.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "event envelope is required");
        }
        Map<String, Object> normalized = new LinkedHashMap<>(event);
        String schemaVersion = text(event, "schemaVersion", "soar.event");
        if (!"soar.event".equals(schemaVersion)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "schemaVersion must be soar.event");
        }
        String eventId = text(event, "eventId", text(event, "id", null));
        if (eventId == null || eventId.isBlank() || eventId.length() > 255
                || containsControl(eventId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "eventId is required and must be at most 255 characters");
        }
        String eventType = text(event, "eventType", text(event, "type", null));
        if (eventType == null || !eventType.matches("[A-Za-z][A-Za-z0-9_.-]{0,63}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "eventType is required and must be a namespaced event type");
        }
        String eventTenant = text(event, "tenantId", text(event, "tenant_id", null));
        if (eventTenant != null && !tenant.equals(eventTenant)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "event tenant does not match the authenticated tenant");
        }

        String occurredAt = text(event, "occurredAt", null);
        if (occurredAt == null) occurredAt = text(event, "occurred_at", null);
        if (occurredAt == null) occurredAt = Instant.now().toString();
        if (occurredAt.length() > 64) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "occurredAt is too long");
        }
        try {
            Instant.parse(occurredAt);
        } catch (Exception failure) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "occurredAt must be an ISO-8601 instant");
        }
        String producer = text(event, "producer", "soar-api");
        if (producer == null || producer.isBlank() || producer.length() > 128
                || containsControl(producer)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "producer must be a non-blank value of at most 128 characters");
        }

        Map<String, Object> subject = object(event.get("subject"), "subject");
        if (subject.isEmpty()) subject = new LinkedHashMap<>(Map.of("type", eventType, "id", eventId));
        String subjectType = stringValue(subject.get("type"));
        String subjectId = stringValue(subject.get("id"));
        if (subjectType == null || !subjectType.matches("[A-Za-z][A-Za-z0-9_.:-]{0,63}")
                || subjectId == null || subjectId.isBlank() || subjectId.length() > 255
                || containsControl(subjectId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "subject.type and subject.id are required and bounded");
        }
        Map<String, Object> data = object(event.get("data"), "data");
        Map<String, Object> traceInput = object(event.get("trace"), "trace");
        Map<String, Object> trace = new LinkedHashMap<>();
        copyTraceText(traceInput, trace, "traceparent", 256, null);
        copyTraceText(traceInput, trace, "correlationId", 255, eventId);
        copyTraceText(traceInput, trace, "causationId", 255, eventId);
        Object depthValue = traceInput.get("automationDepth");
        int depth = depthValue == null ? 0 : strictInteger(depthValue, "trace.automationDepth");
        if (depth < 0 || depth > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "trace.automationDepth must be between 0 and 100");
        }
        trace.put("automationDepth", depth);

        normalized.put("schemaVersion", schemaVersion);
        normalized.put("eventId", eventId);
        normalized.put("id", eventId);
        normalized.put("eventType", eventType);
        normalized.put("type", eventType);
        normalized.put("tenantId", tenant);
        if (event.containsKey("tenant_id")) normalized.put("tenant_id", tenant);
        normalized.put("occurredAt", occurredAt);
        normalized.put("producer", producer);
        normalized.put("subject", subject);
        normalized.put("data", data);
        normalized.put("trace", trace);
        return normalized;
    }

    boolean triggerMatches(String trigger, Map<String, Object> event) {
        String type = text(event, "type");
        if (type == null) type = text(event, "eventType");
        return "ANY".equals(trigger) || (type != null && trigger.equalsIgnoreCase(type));
    }

    boolean activeAt(SoarAutomationRuleEntity rule, Instant now) {
        return (rule.getValidFrom() == null || !now.isBefore(rule.getValidFrom()))
                && (rule.getValidUntil() == null || now.isBefore(rule.getValidUntil()));
    }

    String groupKey(SoarAutomationRuleEntity rule, Map<String, Object> event) {
        String path = rule.getGroupBy();
        if (path == null || path.isBlank()) return "";
        String value = String.valueOf(valueAtPath(event, path));
        return value.length() > 512 ? value.substring(0, 512) : value;
    }

    int automationDepth(Map<String, Object> event) {
        Object trace = event == null ? null : event.get("trace");
        Object value = trace instanceof Map<?, ?> map ? map.get("automationDepth")
                : event == null ? null : event.get("automationDepth");
        if (value instanceof Number number) return number.intValue();
        try {
            return value == null ? 0 : Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    /** Carry a bounded hop counter through the durable run input. */
    Map<String, Object> automationInputs(Map<String, Object> event, int nextDepth) {
        Map<String, Object> inputs = new LinkedHashMap<>();
        if (event != null) inputs.putAll(event);
        Map<String, Object> trace = new LinkedHashMap<>();
        Object existing = inputs.get("trace");
        if (existing instanceof Map<?, ?> map) {
            map.forEach((key, value) -> trace.put(String.valueOf(key), value));
        }
        trace.put("automationDepth", Math.max(0, Math.min(6, nextDepth)));
        inputs.put("trace", trace);
        inputs.put("automationDepth", Math.max(0, Math.min(6, nextDepth)));
        return inputs;
    }

    boolean conditionMatches(String json, Map<String, Object> event) {
        JsonNode condition = read(json);
        if (condition.isMissingNode() || condition.isNull() || condition.isEmpty()) return true;
        if (condition.has("all") && condition.get("all").isArray()) {
            for (JsonNode item : condition.get("all")) {
                if (!conditionMatches(item.toString(), event)) return false;
            }
            return true;
        }
        if (condition.has("any") && condition.get("any").isArray()) {
            for (JsonNode item : condition.get("any")) {
                if (conditionMatches(item.toString(), event)) return true;
            }
            return false;
        }
        String field = condition.path("field").asText("");
        if (condition.has("expression")) {
            String expression = condition.path("expression").asText("");
            return SoarExpressionEngine.isSafe(expression)
                    && SoarExpressionEngine.evaluate(expression, event);
        }
        if (!field.isBlank()) {
            Object actual = valueAtPath(event, field);
            String operator = condition.path("operator").asText("equals").toLowerCase();
            JsonNode expected = condition.get("value");
            String expectedText = expected == null ? "" : expected.asText();
            String actualText = actual == null ? "" : String.valueOf(actual);
            return switch (operator) {
                case "contains" -> actualText.toLowerCase().contains(expectedText.toLowerCase());
                case "exists" -> actual != null;
                case "not_equals", "not-equals" -> !actualText.equalsIgnoreCase(expectedText);
                case "in" -> expected != null && expected.isArray()
                        && containsIgnoreCase(expected, actualText);
                default -> actualText.equalsIgnoreCase(expectedText);
            };
        }
        // Unknown non-empty conditions must never silently match.
        return false;
    }

    private boolean containsIgnoreCase(JsonNode expected, String actual) {
        for (JsonNode item : expected) {
            if (item.asText().equalsIgnoreCase(actual)) return true;
        }
        return false;
    }

    private JsonNode read(String json) {
        try {
            return mapper.readTree(json == null ? "{}" : json);
        } catch (Exception ignored) {
            return mapper.createObjectNode();
        }
    }

    private static Map<String, Object> object(Object value, String field) {
        if (value == null) return new LinkedHashMap<>();
        if (!(value instanceof Map<?, ?> map)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    field + " must be a JSON object");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        map.forEach((key, item) -> result.put(String.valueOf(key), item));
        return result;
    }

    private static String stringValue(Object value) {
        if (value == null) return null;
        String result = String.valueOf(value).trim();
        return result.isBlank() ? null : result;
    }

    private static void copyTraceText(Map<String, Object> source, Map<String, Object> target,
                                      String field, int max, String fallback) {
        Object value = source.get(field);
        if (value == null) value = fallback;
        if (value == null) return;
        if (!(value instanceof CharSequence)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "trace." + field + " must be a string");
        }
        String text = value.toString().trim();
        if (text.length() > max || containsControl(text)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "trace." + field + " is too long or contains control characters");
        }
        target.put(field, text);
    }

    private static int strictInteger(Object value, String field) {
        if (!(value instanceof Byte || value instanceof Short || value instanceof Integer
                || value instanceof Long)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + " must be an integer");
        }
        long number = ((Number) value).longValue();
        if (number < Integer.MIN_VALUE || number > Integer.MAX_VALUE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + " is out of range");
        }
        return (int) number;
    }

    private static boolean containsControl(String value) {
        if (value == null) return false;
        for (int index = 0; index < value.length(); index++) {
            if (Character.isISOControl(value.charAt(index))) return true;
        }
        return false;
    }

    private static Object valueAtPath(Map<String, Object> values, String path) {
        Object current = values;
        for (String part : path.split("\\.")) {
            if (!(current instanceof Map<?, ?> map)) return null;
            current = map.get(part);
        }
        return current;
    }

    private static String text(Map<String, Object> values, String key) {
        Object value = values == null ? null : values.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private static String text(Map<String, Object> values, String key, String fallback) {
        String value = text(values, key);
        return value == null || value.isBlank() ? fallback : value;
    }
}
