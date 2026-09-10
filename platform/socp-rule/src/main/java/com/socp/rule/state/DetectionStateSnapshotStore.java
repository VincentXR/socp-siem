package com.socp.rule.state;

import java.util.List;
import java.util.Optional;

/** Storage SPI for durable rule-state snapshots. */
public interface DetectionStateSnapshotStore {
    void save(DetectionStateSnapshot snapshot);

    /**
     * Persist one coherent checkpoint generation. Transactional stores should
     * override this method so all rule rows commit together; the default keeps
     * source compatibility for small stores.
     */
    default void saveAll(List<DetectionStateSnapshot> snapshots) {
        if (snapshots == null) return;
        snapshots.forEach(this::save);
    }

    /** Whether saveAll is committed as one checkpoint generation. */
    default boolean supportsAtomicBatch() {
        return false;
    }

    Optional<DetectionStateSnapshot> latest(String tenantId, String ruleId, int shardId);
}
