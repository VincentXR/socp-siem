package com.socp.asset.collect.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.LinkedHashMap;
import java.util.Map;

/** Bounded asset ingestion contract; connector-specific fields can be added deliberately. */
public record AssetCollectRequest(
        @NotBlank @Size(max = 128) String name,
        @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9_-]*") String type,
        @Size(max = 64) String ip,
        @Size(max = 128) String os,
        @Size(max = 128) String owner,
        @Size(max = 32) String criticality
) {
    public Map<String, Object> asMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        put(out, "name", name);
        put(out, "type", type);
        put(out, "ip", ip);
        put(out, "os", os);
        put(out, "owner", owner);
        put(out, "criticality", criticality);
        return out;
    }

    private static void put(Map<String, Object> out, String key, Object value) {
        if (value != null && !(value instanceof String text && text.isBlank())) out.put(key, value);
    }
}
