package com.socp.detect.web.persistence.store;

import com.socp.rule.state.DetectionStateSnapshot;
import org.junit.jupiter.api.Test;

import java.time.Instant;

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
}
