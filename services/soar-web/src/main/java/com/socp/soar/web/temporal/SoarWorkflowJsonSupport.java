package com.socp.soar.web.temporal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Bounded JSON conversion used by the deterministic workflow interpreter. */
final class SoarWorkflowJsonSupport {
    private final ObjectMapper mapper;

    SoarWorkflowJsonSupport(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> readObjects(String json) {
        String source = json == null || json.isBlank() ? "[]" : json;
        try {
            JsonNode value = mapper.readTree(source);
            if (value == null || value.isNull()) return List.of();
            if (!value.isArray()) throw jsonFailure("array", null);
            List<Map<String, Object>> result = new ArrayList<>();
            for (JsonNode item : value) {
                if (item == null || !item.isObject()) throw jsonFailure("array item", null);
                result.add(mapper.convertValue(item, Map.class));
            }
            return result;
        } catch (SoarWorkflowJsonException failure) {
            throw failure;
        } catch (Exception failure) {
            throw jsonFailure("array", failure);
        }
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> readObject(String json) {
        String source = json == null || json.isBlank() ? "{}" : json;
        try {
            JsonNode node = mapper.readTree(source);
            if (node == null || node.isNull()) return new java.util.LinkedHashMap<>();
            if (!node.isObject()) throw jsonFailure("object", null);
            return mapper.convertValue(node, Map.class);
        } catch (SoarWorkflowJsonException failure) {
            throw failure;
        } catch (Exception failure) {
            throw jsonFailure("object", failure);
        }
    }

    String writeJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception failure) {
            throw jsonFailure("serialization", failure);
        }
    }

    /** Malformed durable JSON must fail closed instead of silently changing inputs. */
    private SoarWorkflowJsonException jsonFailure(String kind, Exception cause) {
        return new SoarWorkflowJsonException("invalid workflow JSON (" + kind + ")", cause);
    }

    private static final class SoarWorkflowJsonException extends IllegalStateException {
        private SoarWorkflowJsonException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
