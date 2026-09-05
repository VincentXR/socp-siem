package com.socp.soar.web.connector;

import java.time.Instant;
import java.util.Map;

public record ConnectionTestResult(boolean healthy, String status, String errorCode,
                                   String errorMessage, long durationMs,
                                   Map<String, Object> details, Instant testedAt) {
    public ConnectionTestResult {
        details = details == null ? Map.of() : Map.copyOf(details);
        testedAt = testedAt == null ? Instant.now() : testedAt;
    }

    public static ConnectionTestResult ok(long durationMs, Map<String, Object> details) {
        return new ConnectionTestResult(true, "HEALTHY", null, null, durationMs, details, Instant.now());
    }

    public static ConnectionTestResult failed(String code, String message, long durationMs) {
        return new ConnectionTestResult(false, "UNHEALTHY", code, message, durationMs, Map.of(), Instant.now());
    }
}
