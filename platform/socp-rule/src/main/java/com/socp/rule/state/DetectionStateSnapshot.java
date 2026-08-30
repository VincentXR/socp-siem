package com.socp.rule.state;

import java.time.Instant;

/** Versioned, portable snapshot envelope; state bytes are opaque to the platform. */
public record DetectionStateSnapshot(
        String ruleId,
        String ruleVersion,
        String tenantId,
        int shardId,
        long lastProcessedOffset,
        byte[] serializedState,
        Instant snapshotTimestamp) {
    public DetectionStateSnapshot {
        if (ruleId == null || ruleId.isBlank()) throw new IllegalArgumentException("ruleId is required");
        if (tenantId == null || tenantId.isBlank()) throw new IllegalArgumentException("tenantId is required");
        if (shardId < 0) throw new IllegalArgumentException("shardId must not be negative");
        serializedState = serializedState == null ? new byte[0] : serializedState.clone();
        snapshotTimestamp = snapshotTimestamp == null ? Instant.now() : snapshotTimestamp;
    }

    @Override
    public byte[] serializedState() {
        return serializedState.clone();
    }
}
