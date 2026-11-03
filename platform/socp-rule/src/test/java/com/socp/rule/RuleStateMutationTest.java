package com.socp.rule;

import com.socp.rule.state.RuleStateLimits;
import com.socp.rule.state.RuleStateMap;
import com.socp.rule.state.StateSnapshotCodec;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class RuleStateMutationTest {
    @Test
    void batchUndoCountsSharedEvidenceOnceButRetainsEveryContainerAndDistinctInstance() {
        var event = new com.socp.rule.model.SecurityEvent("shared", java.time.Instant.EPOCH,
                "test", "host", "x".repeat(1024), Map.of("tenant_id", "tenant"), com.socp.rule.model.Severity.INFO);
        var equalCopy = new com.socp.rule.model.SecurityEvent(event.id(), event.timestamp(), event.source(),
                event.host(), event.raw(), event.fields(), event.severity());
        RuleStateMap<Map<String, Object>> state = new RuleStateMap<>();
        state.get("a", () -> new LinkedHashMap<>(Map.of("evidence", StateSnapshotCodec.event(event), "count", 1)));
        state.get("b", () -> new LinkedHashMap<>(Map.of("evidence", StateSnapshotCodec.event(equalCopy), "count", 1)));
        byte[] before = state.snapshot(value -> value);
        try (var shared = new com.socp.rule.state.StateUndoBudget();
             var first = state.beginMutation(value -> value, RuleStateMutationTest::decode)) {
            state.get("a", LinkedHashMap::new).put("count", 2);
            state.invalidateSnapshot("a");
            first.detach();
            long initial = first.retainedBytes(shared);
            try (var second = state.beginMutation(value -> value, RuleStateMutationTest::decode)) {
                state.get("a", LinkedHashMap::new).put("count", 3);
                state.invalidateSnapshot("a");
                second.detach();
                long repeated = second.retainedBytes(shared);
                assertTrue(repeated > 128, "the separate undo map and reference still consume budget");
                assertTrue(repeated < initial, "the same immutable evidence needs only another reference");
                try (var third = state.beginMutation(value -> value, RuleStateMutationTest::decode)) {
                    state.get("b", LinkedHashMap::new).put("count", 2);
                    state.invalidateSnapshot("b");
                    assertEquals(initial, third.retainedBytes(shared), "another instance requires its own cache pin and accounting entry");
                    third.rollback();
                }
                second.rollback();
            }
            first.rollback();
        }
        assertArrayEquals(before, state.snapshot(value -> value));
    }

    private static Map<String, Object> decode(Object value) {
        Map<String, Object> result = new LinkedHashMap<>();
        ((Map<?, ?>) value).forEach((key, item) -> result.put(String.valueOf(key), item));
        return result;
    }

    @Test
    void undoAndAuditOnlyEncodeTouchedKeysRegardlessOfTotalCardinality() {
        RuleStateMap<Map<String, Object>> state = new RuleStateMap<>();
        for (int i = 0; i < 10_000; i++) state.get("key-" + i, () -> new LinkedHashMap<>(Map.of("count", 1)));
        byte[] before = state.snapshot(value -> value);
        AtomicInteger encodes = new AtomicInteger();
        try (var mutation = state.beginMutation(value -> { encodes.incrementAndGet(); return value; },
                RuleStateMutationTest::decode)) {
            state.get("key-42", LinkedHashMap::new).put("count", 2);
            state.invalidateSnapshot("key-42");
            state.get("new", LinkedHashMap::new).put("count", 1);
            var change = mutation.change("rule");
            assertTrue(change.changed());
            assertEquals("affected-keys-merkle-v1", change.digestScope());
            assertEquals(3, encodes.get(), "one old key and two resulting keys, not 10,000 keys");
            mutation.rollback();
            assertFalse(mutation.change("rule").changed());
        }
        assertArrayEquals(before, state.snapshot(value -> value));
    }

    @Test
    void rollbackRestoresEvictedKeysAndRemovesInsertedKeysWithoutExceedingCacheBudget() {
        RuleStateMap<Map<String, Object>> state = new RuleStateMap<>(new RuleStateLimits(2, Duration.ofMinutes(1)));
        state.get("old", () -> new LinkedHashMap<>(Map.of("count", 1)));
        state.get("kept", () -> new LinkedHashMap<>(Map.of("count", 2)));
        byte[] before = state.snapshot(value -> value);
        try (var mutation = state.beginMutation(value -> value, RuleStateMutationTest::decode)) {
            state.get("new", () -> new LinkedHashMap<>(Map.of("count", 3)));
            assertFalse(StateSnapshotCodec.read(state.snapshot(value -> value)).containsKey("old"));
            mutation.rollback();
        }
        assertArrayEquals(before, state.snapshot(value -> value));
        assertEquals(2, state.size());
        assertTrue(((Number) state.stats().get("stateSnapshotCacheBytes")).longValue()
                <= ((Number) state.stats().get("stateSnapshotCacheMaxBytes")).longValue());
    }

    @Test
    void unchangedMutationAndRollbackAreRepeatableAndScopesCannotOverlap() {
        RuleStateMap<Map<String, Object>> state = new RuleStateMap<>();
        state.get("a", () -> new LinkedHashMap<>(Map.of("count", 1)));
        try (var mutation = state.beginMutation(value -> value, RuleStateMutationTest::decode)) {
            var empty = mutation.change("rule");
            assertFalse(empty.changed());
            assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", empty.beforeDigest());
            assertEquals(empty.beforeDigest(), empty.afterDigest());
            assertSame(empty, mutation.change("rule"), "unchanged rules must not allocate new digest evidence per event");
            assertThrows(IllegalStateException.class,
                    () -> state.beginMutation(value -> value, RuleStateMutationTest::decode));
            state.get("a", LinkedHashMap::new).put("count", 2);
            state.invalidateSnapshot("a");
            assertTrue(mutation.change("rule").changed(), "a touched key cannot reuse empty evidence");
            mutation.rollback();
            mutation.rollback();
            assertFalse(mutation.change("rule").changed());
        }
        try (var next = state.beginMutation(value -> value, RuleStateMutationTest::decode)) {
            assertFalse(next.change("rule").changed());
        }
    }

    @Test
    void statsReaderCannotExpireKeysWhileDetachedUndoIsAwaitingCommit() throws Exception {
        RuleStateMap<Map<String, Object>> state = new RuleStateMap<>(new RuleStateLimits(10, Duration.ofNanos(1)));
        state.get("a", () -> new LinkedHashMap<>(Map.of("count", 1)));
        try (var mutation = state.beginMutation(value -> value, RuleStateMutationTest::decode)) {
            state.get("a", LinkedHashMap::new).put("count", 2);
            mutation.detach();
            try (var reader = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
                assertEquals(1, reader.submit(state::size).get(5, java.util.concurrent.TimeUnit.SECONDS));
                assertThrows(java.util.concurrent.ExecutionException.class, () -> reader.submit(() ->
                        state.beginMutation(value -> value, RuleStateMutationTest::decode))
                        .get(5, java.util.concurrent.TimeUnit.SECONDS));
            }
            mutation.rollback();
            assertEquals(1, state.get("a", LinkedHashMap::new).get("count"));
        }
        assertEquals(0, state.size(), "expired keys become eligible after the transaction closes");
    }
}
