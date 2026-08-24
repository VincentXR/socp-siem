package com.socp.soar.web.service;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/** Immutable context passed to one SOAR action handler. */
public record PlaybookActionContext(
        String action,
        Map<String, Object> alarm,
        String idempotencyKey,
        boolean simulationAllowed
) {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public String payloadJson() {
        Map<String, Object> payload = new LinkedHashMap<>();
        if (alarm != null) payload.putAll(alarm);
        // Downstream services can use this key as their durable deduplication boundary.
        payload.put("idempotencyKey", idempotencyKey);
        payload.put("soarAction", action);
        try {
            return MAPPER.writeValueAsString(payload);
        } catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
            throw new IllegalArgumentException("cannot serialize playbook action context", failure);
        }
    }
}
