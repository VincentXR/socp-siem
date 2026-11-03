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
    void checkpointAndBase64TransferKeepStateBytesPrivate() {
        byte[] input = {0, 1, -1, 42};
        String expected = java.util.Base64.getEncoder().encodeToString(input);
        var state = new com.socp.rule.engine.RuleEngine.RuleState("rule", "v1", input);
        input[0] = 99;
        var snapshot = state.checkpoint("tenant", 2, 17L, Instant.EPOCH,
                Map.of(0, 17L), Map.of(0, 3L), "events");
        java.util.Arrays.fill(state.serializedState(), (byte) 99);
        java.util.Arrays.fill(snapshot.serializedState(), (byte) 99);
        assertEquals(expected, snapshot.base64State());
        assertEquals(expected, java.util.Base64.getEncoder().encodeToString(state.serializedState()));
        assertEquals("rule", snapshot.ruleId());
        assertEquals("v1", snapshot.ruleVersion());
        assertEquals(Map.of(0, 17L), snapshot.partitionOffsets());
        assertEquals(Map.of(0, 3L), snapshot.partitionOwnerEpochs());
        assertEquals("events", snapshot.inputTopic());
    }

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
