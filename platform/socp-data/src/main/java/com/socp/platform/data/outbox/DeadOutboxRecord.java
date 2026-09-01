package com.socp.platform.data.outbox;

import java.time.Instant;

/** Payload-safe operator view of a terminal outbox row. */
public record DeadOutboxRecord(
        String id,
        String outbox,
        String reference,
        int attempts,
        Instant createdAt,
        Instant updatedAt,
        String lastError) {
}
