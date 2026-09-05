package com.socp.soar.web.connector;

import java.time.Duration;
import java.util.Map;
import java.util.List;
import java.util.Collections;
import java.util.LinkedHashMap;

/** Activity-only connection context. Secret values never cross the workflow boundary. */
public record ConnectionContext(String tenantId, String connectionId, int revision,
                                String connectorType, String endpoint,
                                Map<String, Object> config, Map<String, String> secretRefs,
                                SecretResolver secretResolver, Duration timeout, List<String> allowedHosts) {
    public ConnectionContext {
        config = immutableMap(config);
        secretRefs = immutableMap(secretRefs);
        timeout = timeout == null ? Duration.ofSeconds(30) : timeout;
        allowedHosts = allowedHosts == null ? List.of() : List.copyOf(allowedHosts);
    }

    public ConnectionContext(String tenantId, String connectionId, int revision, String connectorType,
                             String endpoint, Map<String, Object> config, Map<String, String> secretRefs,
                             SecretResolver secretResolver, Duration timeout) {
        this(tenantId, connectionId, revision, connectorType, endpoint, config, secretRefs,
                secretResolver, timeout, List.of());
    }

    public String resolveSecret(String name) {
        String ref = secretRefs.get(name);
        if (ref == null || ref.isBlank()) return null;
        return secretResolver.resolve(ref).orElseThrow(() ->
                new IllegalStateException("SOAR_SECRET_RESOLUTION_FAILED: " + name));
    }

    private static <K, V> Map<K, V> immutableMap(Map<K, V> value) {
        return value == null || value.isEmpty() ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(value));
    }
}
