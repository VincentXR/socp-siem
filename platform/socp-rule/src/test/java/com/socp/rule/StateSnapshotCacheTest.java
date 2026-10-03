package com.socp.rule;

import com.socp.rule.engine.EventAlertSink;
import com.socp.rule.engine.RuleEngine;
import com.socp.rule.model.Alert;
import com.socp.rule.model.SecurityEvent;
import com.socp.rule.model.Severity;
import com.socp.rule.rules.ThresholdRule;
import com.socp.rule.rules.CorrelationRule;
import com.socp.rule.rules.CorrelationSetRule;
import com.socp.rule.rules.BaselineRule;
import com.socp.rule.rules.RareValueRule;
import com.socp.rule.state.RuleStateLimits;
import com.socp.rule.state.RuleStateMap;
import com.socp.rule.state.StatefulRule;
import com.socp.rule.state.StateSnapshotCodec;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.*;

class StateSnapshotCacheTest {
    @Test void cacheMatchesUncachedBytesAcrossAllRulesChangedUnchangedLateRestoreAndReplay() {
        for (Supplier<StatefulRule> factory : factories()) {
            StatefulRule cached = withLimit(8192, factory), plain = withLimit(0, factory);
            for (int i = 0; i < 30; i++) {
                SecurityEvent event = event(i, i % 4 == 0 ? 1 : 100 + i);
                cached.accept(event); plain.accept(event);
                assertArrayEquals(plain.snapshotState(), cached.snapshotState());
                assertArrayEquals(plain.snapshotState(), cached.snapshotState(), "unchanged snapshot");
                assertEquals(plain.drain(), cached.drain());
                if (i == 15) {
                    byte[] old = plain.snapshotState();
                    cached.restoreState(old); plain.restoreState(old);
                    assertArrayEquals(plain.snapshotState(), cached.snapshotState(), "restore invalidates cache");
                }
            }
        }
    }

    @Test void cachedSnapshotsPreserveDurableSinkFailureRollbackAndRetryForAllRules() {
        for (Supplier<StatefulRule> factory : factories()) {
            StatefulRule cached = withLimit(8192, factory);
            AtomicBoolean fail = new AtomicBoolean(false);
            EventAlertSink sink = new EventAlertSink() {
                @Override public void publish(SecurityEvent event, List<Alert> alerts) { if (fail.get()) throw new IllegalStateException("fixture sink failure"); }
                @Override public void close() { }
            };
            try (RuleEngine engine = new RuleEngine(List.of(cached), List.of(sink))) {
                engine.start();
                for (int i = 0; i < 12; i++) engine.ingestAndAwait(event(i, 100 + i)).join();
                byte[] before = cached.snapshotState();
                fail.set(true);
                assertThrows(java.util.concurrent.CompletionException.class,
                        () -> engine.ingestAndAwait(event(40, 140)).join());
                assertArrayEquals(before, cached.snapshotState(), "sink failure rollback");
                fail.set(false);
                // Durable completion failure occurs even when this particular rule produces no alert.
                assertThrows(java.util.concurrent.CompletionException.class,
                        () -> engine.ingestAndAwait(event(40, 140), () -> { throw new IllegalStateException("commit fixture"); }).join());
                assertArrayEquals(before, cached.snapshotState(), "whole-event rollback");
                StatefulRule expected = withLimit(0, factory); expected.restoreState(before);
                expected.accept(event(40, 140));
                engine.ingestAndAwait(event(40, 140)).join();
                assertArrayEquals(expected.snapshotState(), cached.snapshotState(), "retry state");
            }
        }
    }

    @Test void byteBudgetEvictionClearAndEscapedKeysRemainExact() {
        String previous = System.getProperty("socp.rule.snapshot-cache.max-bytes");
        System.setProperty("socp.rule.snapshot-cache.max-bytes", "64");
        try {
            RuleStateMap<List<String>> state = new RuleStateMap<>(new RuleStateLimits(5, Duration.ofMinutes(5)));
            AtomicInteger encodes = new AtomicInteger();
            for (int i = 0; i < 100; i++) state.get("quoted\\\"" + i, ArrayList::new).add("value");
            byte[] first = state.snapshot(value -> { encodes.incrementAndGet(); return value; });
            assertEquals(state.size(), StateSnapshotCodec.read(first).size());
            assertTrue(((Number) state.stats().get("stateSnapshotCacheBytes")).longValue() <= 64);
            assertArrayEquals(first, state.snapshot(value -> value));
            state.clear();
            assertEquals(0L, state.stats().get("stateSnapshotCacheBytes"));
            assertArrayEquals("{}".getBytes(java.nio.charset.StandardCharsets.UTF_8), state.snapshot(value -> value));
        } finally { restoreProperty(previous); }
    }

    @Test void unchangedMembersAreReusedWhileInvalidationAndEvictionReleaseCache() {
        String previous = System.getProperty("socp.rule.snapshot-cache.max-bytes");
        System.setProperty("socp.rule.snapshot-cache.max-bytes", "8192");
        try {
            RuleStateMap<List<String>> state = new RuleStateMap<>(new RuleStateLimits(2, Duration.ofMinutes(5)));
            String firstKey = "quoted\"\\\n中文\uD83D\uDE80";
            List<String> first = state.get(firstKey, ArrayList::new);
            first.add("one");
            state.get("second", ArrayList::new).add("two");
            AtomicInteger encodes = new AtomicInteger();
            java.util.function.Function<List<String>, Object> encode = value -> {
                encodes.incrementAndGet();
                return List.copyOf(value);
            };
            byte[] original = state.snapshot(encode);
            assertEquals(2, encodes.get());
            assertArrayEquals(uncached(state), original);
            assertArrayEquals(original, state.snapshot(encode));
            assertEquals(2, encodes.get(), "unchanged members reuse their serialized bytes");
            original[0] = '!';
            assertArrayEquals(uncached(state), state.snapshot(encode), "returned bytes do not own cache storage");
            synchronized (first) {
                state.invalidateSnapshot(firstKey);
                first.add("changed");
            }
            assertArrayEquals(uncached(state), state.snapshot(encode));
            assertEquals(3, encodes.get(), "only the changed member is re-encoded");
            long beforeEviction = ((Number) state.stats().get("stateSnapshotCacheBytes")).longValue();
            state.get("third", ArrayList::new).add("three");
            assertTrue(((Number) state.stats().get("stateSnapshotCacheBytes")).longValue() < beforeEviction);
            assertArrayEquals(uncached(state), state.snapshot(encode));
            assertEquals(4, encodes.get(), "the surviving member stays cached across eviction");
            state.clear();
            assertEquals(0L, state.stats().get("stateSnapshotCacheBytes"));
            assertArrayEquals(uncached(state), state.snapshot(encode));
            assertEquals(4, encodes.get());
        } finally { restoreProperty(previous); }
    }

    private static byte[] uncached(RuleStateMap<List<String>> state) {
        Map<String, Object> values = new LinkedHashMap<>();
        state.forEach((key, value) -> { synchronized (value) { values.put(key, List.copyOf(value)); } });
        return StateSnapshotCodec.write(values);
    }

    @Test void callerCannotMutateQueuedEvidenceOrCachedSnapshots() {
        Map<String, String> fields = new LinkedHashMap<>(); fields.put("tenant_id", "test"); fields.put("value", "first");
        SecurityEvent event = new SecurityEvent("stable", Instant.EPOCH, "auth", "host", "raw", fields, Severity.INFO);
        fields.put("value", "changed");
        assertEquals("first", event.get("value"));
        assertThrows(UnsupportedOperationException.class, () -> event.fields().put("value", "changed"));
    }

    private static StatefulRule withLimit(long limit, Supplier<StatefulRule> factory) {
        String previous = System.getProperty("socp.rule.snapshot-cache.max-bytes");
        try { System.setProperty("socp.rule.snapshot-cache.max-bytes", String.valueOf(limit)); return factory.get(); }
        finally { restoreProperty(previous); }
    }
    private static void restoreProperty(String value) {
        if (value == null) System.clearProperty("socp.rule.snapshot-cache.max-bytes");
        else System.setProperty("socp.rule.snapshot-cache.max-bytes", value);
    }
    private static List<Supplier<StatefulRule>> factories() { return List.of(
            () -> new ThresholdRule("threshold", "threshold", e -> !"skip".equals(e.source()), SecurityEvent::host, 3, Duration.ofSeconds(30), Severity.HIGH, "threshold"),
            () -> new CorrelationRule("sequence", "sequence", SecurityEvent::host, List.of(e -> "a".equals(e.get("phase")), e -> "b".equals(e.get("phase"))), Duration.ofSeconds(30), Severity.HIGH, "sequence"),
            () -> new CorrelationSetRule("set", "set", SecurityEvent::host, List.of(e -> "a".equals(e.get("phase")), e -> "b".equals(e.get("phase"))), Duration.ofSeconds(30), Severity.HIGH, "set"),
            () -> new RareValueRule("rare", "rare", e -> !"skip".equals(e.source()), SecurityEvent::host, e -> e.get("value"), "value", 2, Severity.HIGH, "rare"),
            () -> new BaselineRule("baseline", "baseline", e -> !"skip".equals(e.source()), SecurityEvent::host, Duration.ofSeconds(10), 3, 2, 1.0, 1, Severity.HIGH, "baseline")); }
    private static SecurityEvent event(int i, int second) { return new SecurityEvent("event-"+i, Instant.ofEpochSecond(second),
            i % 7 == 0 ? "skip" : "auth", "host-"+(i % 3), "raw", Map.of("tenant_id", "test", "value", "v"+i,
            "phase", i % 2 == 0 ? "a" : "b"), Severity.INFO); }
}
