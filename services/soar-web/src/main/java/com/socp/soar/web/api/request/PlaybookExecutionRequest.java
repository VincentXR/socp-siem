package com.socp.soar.web.api.request;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.Map;

/** Extensible manual-execution context with an explicit HTTP boundary and limits. */
public final class PlaybookExecutionRequest {

    private static final int MAX_FIELDS = 128;
    private static final int MAX_APPROX_BYTES = 256 * 1024;

    private final Map<String, Object> values = new LinkedHashMap<>();

    @JsonAnySetter
    public void put(String name, Object value) {
        if (name == null || name.isBlank()) {
            throw invalid("context contains a blank field name");
        }
        if (!values.containsKey(name) && values.size() >= MAX_FIELDS) {
            throw tooLarge("context contains too many fields");
        }
        values.put(name, value);
        if (values.toString().length() > MAX_APPROX_BYTES) {
            values.remove(name);
            throw tooLarge("context exceeds the 256 KiB limit");
        }
    }

    public Map<String, Object> context() {
        return Map.copyOf(values);
    }

    private static ResponseStatusException tooLarge(String message) {
        return new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, message);
    }

    private static ResponseStatusException invalid(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
