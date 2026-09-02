package com.socp.gateway.api.health;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** Cached service health state exposed to the authenticated workbench. */
public record HealthSnapshot(
        String status,
        Map<String, String> services,
        Instant checkedAt) {

    public HealthSnapshot {
        services = Map.copyOf(new LinkedHashMap<>(services));
    }
}
