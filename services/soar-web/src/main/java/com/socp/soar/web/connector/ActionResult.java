package com.socp.soar.web.connector;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Normalized connector outcome. No credential is allowed in output or receipt. */
public record ActionResult(String status, String operationId, Map<String, Object> output,
                           boolean retryable, String errorCode, String errorMessage,
                           Instant remoteTime, Map<String, Object> receipt) {
    public ActionResult {
        output = immutableMap(output);
        receipt = immutableMap(receipt);
        status = status == null ? "FAILED" : status.toUpperCase(java.util.Locale.ROOT);
    }

    private static <K, V> Map<K, V> immutableMap(Map<K, V> value) {
        return value == null || value.isEmpty() ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(value));
    }

    public static ActionResult success(String operationId, Map<String, Object> output,
                                       Map<String, Object> receipt) {
        return new ActionResult("SUCCEEDED", operationId, output, false, null, null,
                null, receipt);
    }

    public static ActionResult failed(String code, String message, boolean retryable) {
        return new ActionResult("FAILED", null, Map.of(), retryable, code, message,
                null, Map.of());
    }

    public static ActionResult unknown(String code, String message) {
        return new ActionResult("UNKNOWN", null, Map.of(), false, code, message,
                null, Map.of());
    }
}
