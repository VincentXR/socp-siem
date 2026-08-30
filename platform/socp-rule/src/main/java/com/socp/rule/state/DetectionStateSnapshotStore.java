package com.socp.rule.state;

import java.util.Optional;

/** Storage SPI for durable rule-state snapshots. */
public interface DetectionStateSnapshotStore {
    void save(DetectionStateSnapshot snapshot);

    Optional<DetectionStateSnapshot> latest(String tenantId, String ruleId, int shardId);
}
