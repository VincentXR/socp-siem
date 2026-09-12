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
        Map<Integer, Long> partitionOffsets,
        Map<Integer, Long> partitionOwnerEpochs,
        String inputTopic) {

    /** Compatibility constructor for the checkpoint-vector format. */
    public DetectionStateSnapshot(String ruleId, String ruleVersion, String tenantId,
                                  int shardId, long lastProcessedOffset,
                                  byte[] serializedState, Instant snapshotTimestamp,
                                  Map<Integer, Long> partitionOffsets) {
        this(ruleId, ruleVersion, tenantId, shardId, lastProcessedOffset, serializedState,
                snapshotTimestamp, partitionOffsets, Map.of(), null);
    }

    /**
     * Compatibility constructor for snapshots written before the checkpoint
     * vector was introduced.  Such snapshots can still be read, but recovery
     * must use the timestamp tail and cannot claim offset-exact replay.
     */
    public DetectionStateSnapshot(String ruleId, String ruleVersion, String tenantId,
                                  int shardId, long lastProcessedOffset,
                                  byte[] serializedState, Instant snapshotTimestamp) {
        this(ruleId, ruleVersion, tenantId, shardId, lastProcessedOffset,
                serializedState, snapshotTimestamp, Map.of(), Map.of(), null);
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
        if (partitionOwnerEpochs == null) partitionOwnerEpochs = Map.of();
        Map<Integer, Long> normalizedEpochs = new LinkedHashMap<>();
        partitionOwnerEpochs.forEach((partition, epoch) -> {
            if (partition == null || partition < 0) {
                throw new IllegalArgumentException("owner epoch partition key must not be negative");
            }
            if (epoch == null || epoch < 0) {
                throw new IllegalArgumentException("owner epoch must not be negative");
            }
            normalizedEpochs.put(partition, epoch);
        });
        if (!normalizedOffsets.keySet().containsAll(normalizedEpochs.keySet())) {
            throw new IllegalArgumentException("owner epoch partition must have a checkpoint offset");
        }
        partitionOwnerEpochs = Map.copyOf(normalizedEpochs);
        inputTopic = inputTopic == null || inputTopic.isBlank() ? null : inputTopic.trim();
        serializedState = serializedState == null ? new byte[0] : serializedState.clone();
        snapshotTimestamp = snapshotTimestamp == null ? Instant.now() : snapshotTimestamp;
    }

    /** Compatibility constructor for snapshots with owner epochs but no topic binding. */
    public DetectionStateSnapshot(String ruleId, String ruleVersion, String tenantId,
                                  int shardId, long lastProcessedOffset,
                                  byte[] serializedState, Instant snapshotTimestamp,
                                  Map<Integer, Long> partitionOffsets,
                                  Map<Integer, Long> partitionOwnerEpochs) {
        this(ruleId, ruleVersion, tenantId, shardId, lastProcessedOffset, serializedState,
                snapshotTimestamp, partitionOffsets, partitionOwnerEpochs, null);
    }

    @Override
    public byte[] serializedState() {
        return serializedState.clone();
    }

    @Override
    public Map<Integer, Long> partitionOffsets() {
        return partitionOffsets;
    }

    @Override
    public Map<Integer, Long> partitionOwnerEpochs() {
        return partitionOwnerEpochs;
    }
}
