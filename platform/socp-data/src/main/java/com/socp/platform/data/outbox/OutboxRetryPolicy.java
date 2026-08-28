package com.socp.platform.data.outbox;

import java.time.Instant;

/** Shared retry/dead-letter decision used by durable outbox publishers. */
public final class OutboxRetryPolicy {

    private OutboxRetryPolicy() {
    }

    public static Decision afterClaim(int attempts, int maxAttempts, Instant now,
                                      String error, long maxDelaySeconds) {
        int normalizedAttempts = Math.max(1, attempts);
        String safeError = truncate(error, 1024);
        if (normalizedAttempts >= Math.max(1, maxAttempts)) {
            return new Decision(normalizedAttempts, true, now, safeError);
        }
        int exponent = Math.min(10, normalizedAttempts);
        long delay = Math.min(Math.max(1, maxDelaySeconds), 1L << exponent);
        return new Decision(normalizedAttempts, false, now.plusSeconds(delay), safeError);
    }

    public static String truncate(String value, int maxLength) {
        if (value == null || value.isBlank()) return "unknown";
        int limit = Math.max(1, maxLength);
        return value.length() <= limit ? value : value.substring(0, limit);
    }

    public record Decision(int attempts, boolean exhausted, Instant nextAttemptAt, String error) {
    }
}
