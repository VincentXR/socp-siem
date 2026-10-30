package com.socp.rule;

import com.socp.rule.state.RuleStateLimits;
import com.socp.rule.state.RuleStateMap;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuleStateMutationCompatibilityTest {
    private static List<String> decode(Object raw) {
        return ((List<?>) raw).stream().map(Object::toString)
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
    }

    @Test
    void touchesOneEntityAtHighCardinalityAndRollsBackNestedCollections() {
        RuleStateMap<List<String>> state = new RuleStateMap<>(new RuleStateLimits(5000, Duration.ofMinutes(5)));
        for (int i = 0; i < 5000; i++) state.get("key-" + i, ArrayList::new).add("before");
        byte[] checkpoint = state.snapshot(List::copyOf);
        AtomicInteger encodes = new AtomicInteger();
        try (var mutation = state.beginMutation(value -> {
            encodes.incrementAndGet();
            return List.copyOf(value);
        }, RuleStateMutationCompatibilityTest::decode)) {
            state.get("key-17", ArrayList::new).add("after");
            state.invalidateSnapshot("key-17");
            assertSame(state.get("key-17", ArrayList::new), state.get("key-17", ArrayList::new));
            var change = mutation.change("test");
            assertEquals("affected-keys-merkle-v1", change.digestScope());
            assertTrue(change.changed());
            assertEquals(2, encodes.get());
            mutation.rollback();
        }
        assertArrayEquals(checkpoint, state.snapshot(List::copyOf));
        try (var mutation = state.beginMutation(List::copyOf, RuleStateMutationCompatibilityTest::decode)) {
            assertFalse(mutation.change("test").changed());
            state.get("key-17", ArrayList::new).add("committed");
            state.invalidateSnapshot("key-17");
        }
        assertEquals(List.of("before", "committed"), state.get("key-17", ArrayList::new));
    }

    @Test
    void rollbackRestoresCapacityEvictionsAndKeepsSnapshotCacheBounded() {
        RuleStateMap<List<String>> state = new RuleStateMap<>(new RuleStateLimits(2, Duration.ofMinutes(5)));
        state.get("oldest", ArrayList::new).add("one");
        state.get("second", ArrayList::new).add("two");
        byte[] before = state.snapshot(List::copyOf);
        long cacheBefore = ((Number) state.stats().get("stateSnapshotCacheBytes")).longValue();
        try (var mutation = state.beginMutation(List::copyOf, RuleStateMutationCompatibilityTest::decode)) {
            state.get("third", ArrayList::new).add("three");
            state.invalidateSnapshot("third");
            assertEquals(2, state.size());
            assertTrue(mutation.change("test").changed());
            state.snapshot(List::copyOf);
            mutation.rollback();
            assertFalse(mutation.change("test").changed());
            // A rollback is reusable until close, as required by rule isolation.
            state.get("second", ArrayList::new).add("temporary");
            state.invalidateSnapshot("second");
            mutation.rollback();
        }
        assertArrayEquals(before, state.snapshot(List::copyOf));
        assertEquals(cacheBefore, state.stats().get("stateSnapshotCacheBytes"));
    }

    @Test
    void ttlRemovalsBelongToTheUndoBoundary() throws Exception {
        RuleStateMap<List<String>> state = new RuleStateMap<>(new RuleStateLimits(10, Duration.ofMillis(20)));
        state.get("expired", ArrayList::new).add("restore-me");
        Thread.sleep(40);
        try (var mutation = state.beginMutation(List::copyOf, RuleStateMutationCompatibilityTest::decode)) {
            assertEquals(0, state.size());
            assertTrue(mutation.change("test").changed());
            mutation.rollback();
            // Undo restores the entry's original age as well as its value.
            // A subsequent access must expire it again, not revive stale state.
            assertEquals(List.of(), state.get("expired", ArrayList::new));
            assertEquals(2L, state.evictions());
        }
    }

    @Test
    void lazyAccessExpiresAnIdleEntityBeforeItCanContributeToTheNextWindow() throws Exception {
        RuleStateMap<List<String>> state = new RuleStateMap<>(new RuleStateLimits(10, Duration.ofMillis(500)));
        state.get("entity", ArrayList::new).add("stale-evidence");
        Thread.sleep(550);
        try (var mutation = state.beginMutation(List::copyOf, RuleStateMutationCompatibilityTest::decode)) {
            assertEquals(List.of(), state.get("entity", ArrayList::new));
            state.get("entity", ArrayList::new).add("fresh-evidence");
            assertTrue(mutation.change("test").changed());
        }
        assertEquals(List.of("fresh-evidence"), state.get("entity", ArrayList::new));
    }

    @Test
    void excessiveMutationFailsClosedAndCanStillRestoreEveryOriginalKey() {
        RuleStateMap<List<String>> state = new RuleStateMap<>(new RuleStateLimits(2, Duration.ofMinutes(5)));
        state.get("original", ArrayList::new).add("keep");
        byte[] before = state.snapshot(List::copyOf);
        try (var mutation = state.beginMutation(List::copyOf, RuleStateMutationCompatibilityTest::decode)) {
            state.get("two", ArrayList::new).add("2");
            state.get("three", ArrayList::new).add("3");
            assertThrows(IllegalStateException.class, () -> state.get("four", ArrayList::new));
            mutation.rollback();
        }
        assertArrayEquals(before, state.snapshot(List::copyOf));
    }
}
