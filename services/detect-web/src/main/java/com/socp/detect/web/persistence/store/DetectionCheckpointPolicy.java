package com.socp.detect.web.persistence.store;

import com.socp.rule.state.DetectionStateSnapshot;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/** Shared generation and progress checks for durable and local snapshot stores. */
final class DetectionCheckpointPolicy {
    private DetectionCheckpointPolicy() {}

    static void validateGeneration(List<DetectionStateSnapshot> snapshots) {
        DetectionStateSnapshot first = snapshots.getFirst();
        if (first == null) throw new IllegalArgumentException("snapshot is required");
        var rules = new HashSet<String>();
        for (DetectionStateSnapshot snapshot : snapshots) {
            if (snapshot == null || !first.tenantId().equals(snapshot.tenantId())
                    || first.shardId() != snapshot.shardId()
                    || !first.snapshotTimestamp().equals(snapshot.snapshotTimestamp())
                    || !first.partitionOffsets().equals(snapshot.partitionOffsets())
                    || !rules.add(snapshot.ruleId())) {
                throw new IllegalArgumentException("snapshots must describe one tenant/shard checkpoint generation");
            }
        }
    }

    static boolean canReplace(Instant previousTime, Map<Integer, Long> previousOffsets,
                              DetectionStateSnapshot candidate) {
        if (previousTime == null) return true;
        // Wall clocks are not processing progress. A later timestamp must not
        // overwrite a partition that has already advanced further, nor may an
        // incomplete ownership vector erase another partition's checkpoint.
        if (!previousOffsets.isEmpty()) {
            for (var entry : previousOffsets.entrySet()) {
                if (candidate.partitionOffsets().getOrDefault(entry.getKey(), -1L) < entry.getValue()) {
                    return false;
                }
            }
            if (!previousOffsets.equals(candidate.partitionOffsets())) return true;
        }
        // Equal vectors (and pre-vector rows) use time only as a tie breaker.
        return !previousTime.isAfter(candidate.snapshotTimestamp());
    }
}
