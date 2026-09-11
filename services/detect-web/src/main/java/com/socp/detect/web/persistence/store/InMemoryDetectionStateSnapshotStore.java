package com.socp.detect.web.persistence.store;

import com.socp.rule.state.DetectionStateSnapshot;
import com.socp.rule.state.DetectionStateSnapshotStore;

import java.util.Map;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Deterministic local snapshot store used by tests and the single-node profile. */
public final class InMemoryDetectionStateSnapshotStore implements DetectionStateSnapshotStore {
    private final Map<String, DetectionStateSnapshot> snapshots = new ConcurrentHashMap<>();

    @Override
    public void save(DetectionStateSnapshot snapshot) {
        if (snapshot == null) throw new IllegalArgumentException("snapshot is required");
        saveAll(List.of(snapshot));
    }

    @Override
    public boolean supportsAtomicBatch() {
        return true;
    }

    @Override
    public synchronized void saveAll(List<DetectionStateSnapshot> candidates) {
        if (candidates == null || candidates.isEmpty()) return;
        DetectionCheckpointPolicy.validateGeneration(candidates);
        for (DetectionStateSnapshot candidate : candidates) {
            DetectionStateSnapshot previous = snapshots.get(key(candidate.tenantId(), candidate.ruleId(), candidate.shardId()));
            if (previous != null && !DetectionCheckpointPolicy.canReplace(
                    previous.snapshotTimestamp(), previous.partitionOffsets(), candidate)) return;
        }
        candidates.forEach(candidate -> snapshots.put(
                key(candidate.tenantId(), candidate.ruleId(), candidate.shardId()), candidate));
    }

    @Override
    public synchronized Optional<DetectionStateSnapshot> latest(String tenantId, String ruleId, int shardId) {
        return Optional.ofNullable(snapshots.get(key(tenantId, ruleId, shardId)));
    }

    int size() { return snapshots.size(); }

    private static String key(String tenantId, String ruleId, int shardId) {
        return tenantId + "\u0000" + ruleId + "\u0000" + shardId;
    }
}
