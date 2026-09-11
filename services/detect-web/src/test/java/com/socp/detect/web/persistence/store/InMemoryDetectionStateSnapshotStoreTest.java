package com.socp.detect.web.persistence.store;

import com.socp.rule.state.DetectionStateSnapshot;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class InMemoryDetectionStateSnapshotStoreTest {
    @Test
    void keepsNewestSnapshotPerTenantRuleAndShard() {
        var store = new InMemoryDetectionStateSnapshotStore();
        store.save(new DetectionStateSnapshot("R", "1", "t", 0, 1, new byte[]{1}, Instant.parse("2026-01-01T00:00:00Z")));
        store.save(new DetectionStateSnapshot("R", "1", "t", 0, 2, new byte[]{2}, Instant.parse("2026-01-01T00:00:01Z")));
        assertEquals(2, store.latest("t", "R", 0).orElseThrow().lastProcessedOffset());
        assertEquals(1, store.size());
    }

    @Test
    void staleRowPreventsPartialGenerationAndProgressNeverRegresses() {
        var store = new InMemoryDetectionStateSnapshotStore();
        Instant time = Instant.parse("2026-01-01T00:00:00Z");
        store.save(new DetectionStateSnapshot("A", "1", "t", 0, 8, new byte[]{1}, time, Map.of(0, 8L)));
        store.save(new DetectionStateSnapshot("B", "1", "t", 0, 12, new byte[]{1}, time, Map.of(0, 12L)));
        store.saveAll(List.of(
                new DetectionStateSnapshot("A", "1", "t", 0, 10, new byte[]{2}, time.plusSeconds(1), Map.of(0, 10L)),
                new DetectionStateSnapshot("B", "1", "t", 0, 10, new byte[]{2}, time.plusSeconds(1), Map.of(0, 10L))));
        assertEquals(Map.of(0, 8L), store.latest("t", "A", 0).orElseThrow().partitionOffsets());
        assertEquals(Map.of(0, 12L), store.latest("t", "B", 0).orElseThrow().partitionOffsets());
    }
}
