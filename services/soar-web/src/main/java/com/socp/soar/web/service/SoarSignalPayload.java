package com.socp.soar.web.service;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.socp.soar.web.definition.SoarDefinitionValidator;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;

/** Validated persisted signal; invalid decisions never become default rejection/input. */
record SoarSignalPayload(String type, String key, boolean approve, boolean expired,
                         String inputJson, String resolution, String evidence, String reason) {
    static final int MAX_BYTES = SoarDefinitionValidator.MAX_BYTES + 8192;

    static final class Invalid extends IllegalArgumentException {
        Invalid(String message) { super(message); }
    }

    static SoarSignalPayload parse(ObjectMapper mapper, String type, String storedKey, String json) {
        if (json == null || json.isBlank() || json.length() > MAX_BYTES
                || json.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            throw new Invalid("signal payload is missing or exceeds its byte limit");
        }
        Map<String, Object> payload;
        try {
            payload = mapper.readerFor(new TypeReference<Map<String, Object>>() { })
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION).readValue(json);
        } catch (Exception invalidJson) {
            // Parser messages can contain the decision's sensitive input.
            throw new Invalid("signal payload must be one valid JSON object");
        }
        if (payload == null) throw new Invalid("signal payload must be one valid JSON object");
        String key;
        switch (type == null ? "" : type) {
            case "APPROVAL" -> {
                key = text(payload, "approvalKey", 255, false);
                checkKey(storedKey, key);
                if (!(payload.get("approve") instanceof Boolean approve)) {
                    throw new Invalid("signal approve must be boolean");
                }
                Object expires = payload.getOrDefault("expired", false);
                if (!(expires instanceof Boolean expired)) throw new Invalid("signal expired must be boolean");
                if (expired && (approve || key.isBlank())) throw new Invalid("expired signal requires a gate key and rejection");
                return new SoarSignalPayload(type, key, approve, expired, null, null, null, null);
            }
            case "MANUAL_TASK" -> {
                key = text(payload, "nodeId", 255, false);
                checkKey(storedKey, key);
                Object input = payload.getOrDefault("input", Map.of());
                if (!(input instanceof Map<?, ?>)) throw new Invalid("manual signal input must be an object");
                String encoded;
                try { encoded = mapper.writeValueAsString(input); }
                catch (Exception invalidInput) { throw new Invalid("manual signal input cannot be serialized"); }
                if (encoded.getBytes(StandardCharsets.UTF_8).length > SoarDefinitionValidator.MAX_BYTES) {
                    throw new Invalid("manual signal input exceeds its byte limit");
                }
                return new SoarSignalPayload(type, key, false, false, encoded, null, null, null);
            }
            case "UNKNOWN_RESOLUTION" -> {
                key = text(payload, "nodeId", 255, true);
                checkKey(storedKey, key);
                String resolution = text(payload, "resolution", 32, true);
                if (!Set.of("CONFIRMED_SUCCEEDED", "CONFIRMED_NOT_EXECUTED").contains(resolution)) {
                    throw new Invalid("unknown-result signal has an unsupported resolution");
                }
                return new SoarSignalPayload(type, key, false, false, null, resolution,
                        text(payload, "evidence", 4096, true), text(payload, "reason", 2048, true));
            }
            default -> throw new Invalid("unsupported signal type");
        }
    }

    private static void checkKey(String stored, String payload) {
        if (stored != null && !stored.isBlank() && !stored.equals(payload)) {
            throw new Invalid("signal payload key does not match its durable key");
        }
    }

    private static String text(Map<String, Object> payload, String name, int max, boolean required) {
        Object raw = payload.get(name);
        if (raw == null && !required) return "";
        if (!(raw instanceof String text) || text.length() > max || required && text.isBlank()) {
            throw new Invalid("invalid signal field: " + name);
        }
        return text.trim();
    }
}
