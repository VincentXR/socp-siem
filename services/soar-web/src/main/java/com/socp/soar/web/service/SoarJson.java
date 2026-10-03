package com.socp.soar.web.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;

/** Pure JSON encoding and tolerant persisted-data decoding. */
final class SoarJson {
    private final ObjectMapper mapper;
    SoarJson(ObjectMapper mapper) { this.mapper = mapper; }
    Map<String, Object> readMap(String json) {
        try { Map<String, Object> value = mapper.readValue(json == null ? "{}" : json, Map.class);
            return value == null ? new LinkedHashMap<>() : new LinkedHashMap<>(value); }
        catch (Exception ignored) { return new LinkedHashMap<>(); }
    }

    String shortHash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(String.valueOf(value)
                    .getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(); for (int i = 0; i < 8; i++) out.append(String.format("%02x", digest[i]));
            return out.toString();
        } catch (Exception ignored) { return Integer.toHexString(String.valueOf(value).hashCode()); }
    }

    String write(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (JsonProcessingException failure) { throw new IllegalArgumentException("cannot serialize SOAR data", failure); }
    }

    JsonNode readTree(String value) {
        if (value == null || value.isBlank()) return mapper.createObjectNode();
        try { return mapper.readTree(value); }
        catch (JsonProcessingException ignored) { return mapper.createObjectNode(); }
    }
}
