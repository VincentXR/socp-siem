package com.socp.alert;

import java.time.Instant;

/** Result returned after an operator explicitly requeues a terminal delivery. */
public record OutboxReplayResult(String id, String type, String tenantId, String status, Instant requeuedAt) {
}
