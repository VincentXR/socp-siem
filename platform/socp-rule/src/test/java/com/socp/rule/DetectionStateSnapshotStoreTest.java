package com.socp.rule;

import com.socp.rule.state.DetectionStateSnapshot;
import com.socp.rule.state.DetectionStateSnapshotStore;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class DetectionStateSnapshotStoreTest {

    @Test
    void defaultBatchMethodKeepsSmallStoreImplementationsSourceCompatible() {
        List<DetectionStateSnapshot> saved = new ArrayList<>();
        DetectionStateSnapshotStore store = new DetectionStateSnapshotStore() {
            @Override public void save(DetectionStateSnapshot snapshot) { saved.add(snapshot); }
            @Override public Optional<DetectionStateSnapshot> latest(String tenantId, String ruleId, int shardId) {
                return Optional.empty();
            }
        };
        DetectionStateSnapshot snapshot = new DetectionStateSnapshot(
                "rule", "v1", "tenant", 0, 1L, new byte[]{1}, Instant.EPOCH, Map.of(0, 1L));

        store.saveAll(List.of(snapshot));

        assertEquals(List.of(snapshot), saved);
        assertFalse(store.supportsAtomicBatch());
    }
}
