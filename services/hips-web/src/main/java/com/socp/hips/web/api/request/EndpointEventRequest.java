package com.socp.hips.web.api.request;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.NotBlank;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;

/** Bounded Falco/agent event envelope used by the endpoint collection boundary. */
public record EndpointEventRequest(
        @Size(max = 256) String rule,
        @Size(max = 32) String priority,
        @Size(max = 128) String hostname,
        @Size(max = 4096) String output,
        @Size(max = 128) String agent,
        @Size(max = 64) String type,
        @Size(max = 256) String proc,
        @Size(max = 4096) String cmdline,
        @Size(max = 32) String severity,
        @Size(max = 4096) String message,
        @Size(max = 64) String ts,
        @Size(max = 64) String time,
        @Size(max = 64) String source,
        @Size(max = 64) List<@NotBlank @Size(max = 128) String> tags,
        @JsonProperty("output_fields") @Size(max = 128) Map<String, Object> outputFields,
        @Size(max = 128) Map<String, Object> fields) {

    @AssertTrue(message = "at least one event field is required")
    public boolean isContentPresent() {
        return java.util.stream.Stream.of(rule, hostname, output, message, type)
                .anyMatch(value -> value != null && !value.isBlank());
    }

    @AssertTrue(message = "structured fields require at most 128 scalar entries and 65536 characters")
    public boolean isStructuredContentValid() {
        int entries = 0;
        int characters = 0;
        for (Map<String, Object> values : List.<Map<String, Object>>of(outputFields == null ? Map.of() : outputFields,
                fields == null ? Map.of() : fields)) {
            entries += values.size();
            if (entries > 128) return false;
            for (var entry : values.entrySet()) {
                String key = entry.getKey();
                Object value = entry.getValue();
                if (key == null || key.isBlank() || key.length() > 128) return false;
                if (value != null && !(value instanceof String || value instanceof Number || value instanceof Boolean)) return false;
                if (value instanceof Number number && !Double.isFinite(number.doubleValue())) return false;
                String text = value == null ? "" : value.toString();
                if (text.length() > 4096) return false;
                characters += key.length() + text.length();
                if (characters > 65536) return false;
            }
        }
        return true;
    }

    public Map<String, Object> asMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        put(out, "rule", rule); put(out, "priority", priority); put(out, "hostname", hostname);
        put(out, "output", output); put(out, "agent", agent); put(out, "type", type);
        put(out, "proc", proc); put(out, "cmdline", cmdline); put(out, "severity", severity);
        put(out, "message", message); put(out, "ts", ts);
        put(out, "time", time); put(out, "source", source);
        put(out, "timestamp", time != null && !time.isBlank() ? time : ts);
        put(out, "process", proc);
        if (tags != null) out.put("tags", List.copyOf(tags));
        if (outputFields != null) out.put("output_fields", new LinkedHashMap<>(outputFields));
        if (fields != null) out.put("fields", new LinkedHashMap<>(fields));
        return out;
    }

    private static void put(Map<String, Object> out, String key, String value) {
        if (value != null && !value.isBlank()) out.put(key, value);
    }
}
