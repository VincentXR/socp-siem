package com.socp.soar.web.api.controller;

import com.socp.soar.web.api.request.ReasonRequest;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Shared bounded parsing and pagination helpers for the SOAR HTTP surface. */
final class SoarHttpSupport {

    private SoarHttpSupport() {
    }

    static Map<String, Object> page(Page<Map<String, Object>> result) {
        Map<String, Object> out = new LinkedHashMap<>();
        // The unversioned compatibility surface accepts a legacy zero-based
        // request index. Keep that input stable while returning full metadata.
        out.put("page", result.getNumber());
        out.put("size", result.getSize());
        out.put("total", result.getTotalElements());
        out.put("totalPages", result.getSize() <= 0 ? null : result.getTotalPages());
        out.put("items", result.getContent());
        return out;
    }

    static int clampSize(int size) {
        return Math.min(200, Math.max(1, size));
    }

    static long parseSequence(String value) {
        if (value == null || value.isBlank()) {
            return 0;
        }
        try {
            return Math.max(0, Long.parseLong(value.trim()));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    static Map<String, Object> toObjectMap(Map<?, ?> value) {
        Map<String, Object> output = new LinkedHashMap<>();
        value.forEach((key, item) -> output.put(String.valueOf(key), item));
        return output;
    }

    static String reasonFromLegacy(Object value, String defaultReason) {
        if (value == null) {
            return defaultReason;
        }
        if (value instanceof ReasonRequest request) {
            return request.reason();
        }
        if (value instanceof Map<?, ?> map) {
            return optionalString(toObjectMap(map).get("reason"));
        }
        throw badRequest("request must be an object");
    }

    static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    static String optionalString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    static Long optionalLong(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.valueOf(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    static List<String> optionalStringList(Object value) {
        if (!(value instanceof List<?> list)) {
            return null;
        }
        return list.stream().map(String::valueOf).toList();
    }

    static Instant parseInstant(String value, String field) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value.trim());
        } catch (DateTimeParseException failure) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    field + " must be an ISO-8601 instant", failure);
        }
    }
}
