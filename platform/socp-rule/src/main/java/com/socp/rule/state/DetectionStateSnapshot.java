package com.socp.rule.state;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** Versioned, portable snapshot envelope; state bytes are opaque to the platform. */
public record DetectionStateSnapshot(
        String ruleId,
        String ruleVersion,
        String tenantId,
        int shardId,
        long lastProcessedOffset,
        byte[] serializedState,
        Instant snapshotTimestamp,
        Map<Integer, Long> partitionOffsets) {

    /**
     * Compatibility constructor for snapshots written before the checkpoint
     * vector was introduced.  Such snapshots can still be read, but recovery
     * must use the timestamp tail and cannot claim offset-exact replay.
     */
    public DetectionStateSnapshot(String ruleId, String ruleVersion, String tenantId,
                                  int shardId, long lastProcessedOffset,
                                  byte[] serializedState, Instant snapshotTimestamp) {
        this(ruleId, ruleVersion, tenantId, shardId, lastProcessedOffset,
                serializedState, snapshotTimestamp, Map.of());
    }

    public DetectionStateSnapshot {
        if (ruleId == null || ruleId.isBlank()) throw new IllegalArgumentException("ruleId is required");
        if (tenantId == null || tenantId.isBlank()) throw new IllegalArgumentException("tenantId is required");
        if (shardId < 0) throw new IllegalArgumentException("shardId must not be negative");
        if (partitionOffsets == null) partitionOffsets = Map.of();
        Map<Integer, Long> normalizedOffsets = new LinkedHashMap<>();
        partitionOffsets.forEach((partition, offset) -> {
            if (partition == null || partition < 0) {
                throw new IllegalArgumentException("partition offset key must not be negative");
            }
            if (offset == null || offset < 0) {
                throw new IllegalArgumentException("partition offset must not be negative");
            }
            normalizedOffsets.put(partition, offset);
        });
        partitionOffsets = Map.copyOf(normalizedOffsets);
        serializedState = serializedState == null ? new byte[0] : serializedState.clone();
        snapshotTimestamp = snapshotTimestamp == null ? Instant.now() : snapshotTimestamp;
    }

    @Override
    public byte[] serializedState() {
        return serializedState.clone();
    }

    @Override
    public Map<Integer, Long> partitionOffsets() {
        return partitionOffsets;
    }
}
