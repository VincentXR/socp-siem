package com.socp.rule.time;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Source clock sanity check at trusted ingress, before durable event admission.
 *
 * <p>This is deliberately separate from {@link EventTimePolicy}: rule execution,
 * routed delivery, and journal replay must use the original event time without
 * consulting a changing wall clock. Historical timestamps are never restricted
 * here, and admitted timestamps are never clipped or rewritten.</p>
 */
public record SourceEventTimePolicy(boolean enabled, Duration maxFutureSkew) {

    public SourceEventTimePolicy {
        if (maxFutureSkew == null || maxFutureSkew.isNegative()) {
            throw new IllegalArgumentException("maximum future clock skew must be non-negative");
        }
    }

    public static SourceEventTimePolicy defaults() {
        return new SourceEventTimePolicy(true, Duration.ofSeconds(30));
    }

    /** receivedAt must be a server-generated instant, never a collector field. */
    public void validate(Instant eventTimestamp, Instant receivedAt) {
        Objects.requireNonNull(eventTimestamp, "event timestamp is required");
        Objects.requireNonNull(receivedAt, "ingress receipt time is required");
        // Comparing durations also works for Instant.MIN/MAX without overflowing
        // an Instant when adding the allowance to receivedAt.
        if (enabled && Duration.between(receivedAt, eventTimestamp).compareTo(maxFutureSkew) > 0) {
            throw new IllegalArgumentException(
                    "event timestamp exceeds maximum future clock skew of " + maxFutureSkew);
        }
    }
}
