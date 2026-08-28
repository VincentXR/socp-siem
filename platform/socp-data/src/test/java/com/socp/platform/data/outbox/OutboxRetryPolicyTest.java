package com.socp.platform.data.outbox;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class OutboxRetryPolicyTest {

    @Test
    void appliesBoundedExponentialBackoffAndDeadLetterLimit() {
        Instant now = Instant.parse("2026-08-28T00:00:00Z");

        var retry = OutboxRetryPolicy.afterClaim(3, 5, now, "broker unavailable", 900);
        assertThat(retry.exhausted()).isFalse();
        assertThat(retry.nextAttemptAt()).isEqualTo(now.plusSeconds(8));

        var capped = OutboxRetryPolicy.afterClaim(11, 20, now, "still down", 60);
        assertThat(capped.nextAttemptAt()).isEqualTo(now.plusSeconds(60));

        var dead = OutboxRetryPolicy.afterClaim(5, 5, now, "x".repeat(2000), 900);
        assertThat(dead.exhausted()).isTrue();
        assertThat(dead.error()).hasSize(1024);
    }
}
