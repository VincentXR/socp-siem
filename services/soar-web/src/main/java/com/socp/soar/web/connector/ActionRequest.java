package com.socp.soar.web.connector;

import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Collections;

/** Fully validated action request passed from an Activity to a connector. */
public record ActionRequest(String tenantId, String runId, String nodeRunId,
                            int attemptNo, String actionRef, String idempotencyKey,
                            Map<String, Object> parameters, Map<String, Object> target,
                            ConnectionContext connection) {
    public ActionRequest {
        // JSON objects are allowed to contain explicit nulls.  Map.copyOf
        // rejects those values and would turn a valid connector request into
        // an opaque ACTION_EXCEPTION before the connector can validate it.
        parameters = immutableMap(parameters);
        target = immutableMap(target);
    }

    private static <K, V> Map<K, V> immutableMap(Map<K, V> value) {
        return value == null || value.isEmpty() ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(value));
    }
}
