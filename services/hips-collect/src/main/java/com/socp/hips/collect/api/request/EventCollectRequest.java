package com.socp.hips.collect.api.request;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Size;

import java.util.LinkedHashMap;
import java.util.Map;

/** Bounded Falco/agent event contract. */
public record EventCollectRequest(
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
        @Size(max = 64) String ts
) {
    @AssertTrue(message = "at least one event field is required")
    public boolean hasContent() {
        return rule != null || hostname != null || output != null || message != null || type != null;
    }

    public Map<String, Object> asMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        put(out, "rule", rule); put(out, "priority", priority); put(out, "hostname", hostname);
        put(out, "output", output); put(out, "agent", agent); put(out, "type", type);
        put(out, "proc", proc); put(out, "cmdline", cmdline); put(out, "severity", severity);
        put(out, "message", message); put(out, "ts", ts);
        return out;
    }

    private static void put(Map<String, Object> out, String key, String value) {
        if (value != null && !value.isBlank()) out.put(key, value);
    }
}
