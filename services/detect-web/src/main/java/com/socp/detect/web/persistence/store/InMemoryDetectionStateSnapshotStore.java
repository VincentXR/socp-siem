package com.socp.detect.web.persistence.store;

import com.socp.rule.state.DetectionStateSnapshot;
import com.socp.rule.state.DetectionStateSnapshotStore;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Deterministic local snapshot store used by tests and the single-node profile. */
public final class InMemoryDetectionStateSnapshotStore implements DetectionStateSnapshotStore {
    private final Map<String, DetectionStateSnapshot> snapshots = new ConcurrentHashMap<>();

    @Override
    public void save(DetectionStateSnapshot snapshot) {
        if (snapshot == null) throw new IllegalArgumentException("snapshot is required");
        snapshots.merge(key(snapshot.tenantId(), snapshot.ruleId(), snapshot.shardId()), snapshot,
                (previous, candidate) -> previous.snapshotTimestamp().isAfter(candidate.snapshotTimestamp())
                        ? previous : candidate);
    }

    @Override
    public Optional<DetectionStateSnapshot> latest(String tenantId, String ruleId, int shardId) {
        return Optional.ofNullable(snapshots.get(key(tenantId, ruleId, shardId)));
    }

    int size() { return snapshots.size(); }

    private static String key(String tenantId, String ruleId, int shardId) {
        return tenantId + "\u0000" + ruleId + "\u0000" + shardId;
    }
}
