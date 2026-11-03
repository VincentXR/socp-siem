package com.socp.rule.state;

import com.socp.rule.model.SecurityEvent;
import com.socp.rule.model.Severity;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class StateUndoBudgetTest {
    private static SecurityEvent event(String id) {
        return new SecurityEvent(id, Instant.EPOCH, "test", "host", "x".repeat(16_384),
                Map.of("tenant_id", "tenant"), Severity.INFO);
    }

    private static void churn(String prefix) {
        for (int i = 0; i < 256; i++) StateSnapshotCodec.event(event(prefix + i));
        assertTrue(StateSnapshotCodec.cachedEventBytes() <= 8L * 1024 * 1024);
        assertTrue(StateSnapshotCodec.cachedEvents() <= 4096);
    }

    @Test
    void cacheKeepsEvidenceUntilEveryUndoOwnerReleasesItThenCanEvictNormally() {
        var source = event("pinned");
        var encoded = StateSnapshotCodec.event(source);
        try (var first = new StateUndoBudget(); var second = new StateUndoBudget()) {
            assertEquals(128L, StateSnapshotCodec.retainedBytes(encoded, first));
            assertEquals(8L, StateSnapshotCodec.retainedBytes(encoded, first));
            assertEquals(128L, StateSnapshotCodec.retainedBytes(encoded, second));
            first.close();
            churn("while-pinned-");
            assertSame(encoded, StateSnapshotCodec.event(source));
        }
        churn("after-release-");
        assertNotSame(encoded, StateSnapshotCodec.event(source));
    }

    @Test
    void fullyPinnedCacheStaysBoundedAndUncachedEvidenceConsumesTheUndoBudget() {
        long uncachedBytes = 0;
        int uncached = 0;
        try (var budget = new StateUndoBudget()) {
            for (int i = 0; i < 256; i++) {
                var encoded = StateSnapshotCodec.event(event("saturated-" + i));
                long charge = StateSnapshotCodec.retainedBytes(encoded, budget);
                if (charge > 128) { uncached++; uncachedBytes += charge; }
                assertEquals(8L, StateSnapshotCodec.retainedBytes(encoded, budget));
                assertTrue(StateSnapshotCodec.cachedEventBytes() <= 8L * 1024 * 1024);
                assertTrue(StateSnapshotCodec.cachedEvents() <= 4096);
            }
        }
        assertTrue(uncached > 0);
        assertTrue(uncachedBytes > 4L * 1024 * 1024, "uncached payloads cannot bypass the engine's undo bound");
    }
}
