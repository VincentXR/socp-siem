package com.socp.platform.data.outbox;

import java.time.Instant;

/** Result of an explicit, audited operator action on a terminal outbox row. */
public record OutboxAdminResult(
        String id,
        String outbox,
        String status,
        Instant occurredAt) {
}
