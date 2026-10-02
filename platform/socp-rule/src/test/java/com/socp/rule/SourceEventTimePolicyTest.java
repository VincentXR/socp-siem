package com.socp.rule;

import com.socp.rule.time.SourceEventTimePolicy;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SourceEventTimePolicyTest {

    private static final Instant RECEIVED_AT = Instant.parse("2026-10-02T12:00:00Z");

    @Test
    void defaultAcceptsHistoricalCurrentAndInclusiveFutureBoundaryOnly() {
        var policy = SourceEventTimePolicy.defaults();
        assertDoesNotThrow(() -> policy.validate(Instant.MIN, RECEIVED_AT));
        assertDoesNotThrow(() -> policy.validate(RECEIVED_AT, RECEIVED_AT));
        assertDoesNotThrow(() -> policy.validate(RECEIVED_AT.plusSeconds(30), RECEIVED_AT));
        assertThrows(IllegalArgumentException.class,
                () -> policy.validate(RECEIVED_AT.plusSeconds(30).plusNanos(1), RECEIVED_AT));
        assertThrows(IllegalArgumentException.class, () -> policy.validate(Instant.MAX, RECEIVED_AT));
        assertDoesNotThrow(() -> policy.validate(Instant.MAX, Instant.MAX));
    }

    @Test
    void trustedConfigurationCanTightenExpandOrDisableFutureCheck() {
        var strict = new SourceEventTimePolicy(true, Duration.ZERO);
        assertDoesNotThrow(() -> strict.validate(RECEIVED_AT, RECEIVED_AT));
        assertThrows(IllegalArgumentException.class,
                () -> strict.validate(RECEIVED_AT.plusNanos(1), RECEIVED_AT));
        assertDoesNotThrow(() -> new SourceEventTimePolicy(true, Duration.ofDays(2))
                .validate(RECEIVED_AT.plusSeconds(86_400), RECEIVED_AT));
        assertDoesNotThrow(() -> new SourceEventTimePolicy(false, Duration.ZERO)
                .validate(Instant.MAX, RECEIVED_AT));
        assertThrows(IllegalArgumentException.class,
                () -> new SourceEventTimePolicy(true, Duration.ofSeconds(-1)));
        assertThrows(IllegalArgumentException.class, () -> new SourceEventTimePolicy(false, null));
    }
}
