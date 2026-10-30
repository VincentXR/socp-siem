package com.socp.rule;

import com.socp.rule.engine.BatchEventAlertSink;
import com.socp.rule.engine.DetectionResult;
import com.socp.rule.engine.OrderedCommitGuard;
import com.socp.rule.engine.RuleEngine;
import com.socp.rule.engine.Suppressor;
import com.socp.rule.model.Alert;
import com.socp.rule.model.SecurityEvent;
import com.socp.rule.model.Severity;
import com.socp.rule.rules.ThresholdRule;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class RuleEngineBatchTest {
    @Test
    void everyPositionPrecedesTheFirstCompletionCheckpoint() throws Exception {
        Sink sink = new Sink();
        ThreadLocal<String> context = new ThreadLocal<>();
        sink.context = context;
        var rule = rule(100);
        AtomicLong position = new AtomicLong(-1);
        AtomicLong commitCalls = new AtomicLong();
        var observer = new com.socp.rule.engine.RuleProcessingObserver() {
            @Override public void operationCompleted(Operation operation, int items, long nanos, boolean success) {
                if (operation == Operation.BATCH_COMMIT) {
                    assertTrue(success);
                    assertEquals(8, items);
                    assertTrue(nanos >= 0);
                    commitCalls.incrementAndGet();
                    throw new IllegalStateException("metrics unavailable after durable commit");
                }
            }
        };
        try (RuleEngine engine = new RuleEngine(List.of(rule), List.of(sink), null,
                observer, event -> {
                    context.set(event.id());
                    return context::remove;
                })) {
            List<CompletableFuture<Void>> futures = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                int offset = i;
                futures.add(engine.ingestAndAwait(event(i), () -> position.set(offset), guard()));
            }
            var checkpoint = futures.getFirst().thenRun(() -> engine.captureStateSnapshot(states -> {
                assertEquals(7, position.get(), "all eight state changes must have positions before capture");
                assertEquals(1, states.size());
                return null;
            }));
            engine.start();
            CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).get(5, TimeUnit.SECONDS);
            checkpoint.get(5, TimeUnit.SECONDS);
            assertEquals(List.of(8), sink.batches.stream().map(List::size).toList());
            assertEquals(1, commitCalls.get(), "observer failure must not fail or repeat committed work");
        }
    }

    @Test
    void failedAtomicCommitRollsBackEveryEventAndRetryProducesOneAlert() throws Exception {
        Sink sink = new Sink(); sink.fail = true;
        var rule = rule(3);
        byte[] before = rule.snapshotState();
        try (RuleEngine engine = new RuleEngine(List.of(rule), List.of(sink))) {
            List<CompletableFuture<Void>> futures = new ArrayList<>();
            for (int i = 0; i < 3; i++) futures.add(engine.ingestAndAwait(event(i), null, guard()));
            engine.start();
            for (var future : futures) assertThrows(CompletionException.class, future::join);
            assertArrayEquals(before, rule.snapshotState());
            assertTrue(sink.batches.isEmpty());
            sink.fail = false;
            for (int i = 0; i < 3; i++) engine.ingestAndAwait(event(i), null, guard()).get(5, TimeUnit.SECONDS);
            assertEquals(1, sink.batches.stream().flatMap(List::stream).mapToInt(r -> r.alerts().size()).sum());
        }
    }

    @Test
    void candidateBoundaryPreservesSuppressionAcrossQueuedEvents() throws Exception {
        Sink sink = new Sink();
        try (Suppressor suppressor = new Suppressor(Duration.ofMinutes(1));
             RuleEngine engine = new RuleEngine(List.of(rule(1)), List.of(sink), suppressor)) {
            List<CompletableFuture<Void>> futures = new ArrayList<>();
            for (int i = 0; i < 4; i++) futures.add(engine.ingestAndAwait(event(i), null, guard()));
            engine.start();
            CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).get(5, TimeUnit.SECONDS);
            assertEquals(List.of(1, 1, 1, 1), sink.batches.stream().map(List::size).toList());
            assertEquals(List.of(1, 0, 0, 0), sink.batches.stream().map(b -> b.getFirst().alerts().size()).toList());
        }
    }

    @Test
    void aFailedPostCommitCallbackCannotPublishAnAheadOfPositionCheckpoint() {
        Sink sink = new Sink();
        var rule = rule(100);
        try (RuleEngine engine = new RuleEngine(List.of(rule), List.of(sink))) {
            var first = engine.ingestAndAwait(event(1), () -> { throw new IllegalStateException("fenced"); }, guard());
            var second = engine.ingestAndAwait(event(2), null, guard());
            engine.start();
            assertThrows(CompletionException.class, first::join);
            assertThrows(CompletionException.class, second::join);
            assertEquals(2, sink.batches.getFirst().size(), "durable state must not be undone after commit");
            assertThrows(IllegalStateException.class, engine::snapshotStates);
            assertTrue(new String(rule.snapshotState(), java.nio.charset.StandardCharsets.UTF_8).contains("event-2"));
        }
    }

    private static OrderedCommitGuard guard() { return new OrderedCommitGuard("unit", () -> { }); }
    private static ThresholdRule rule(int threshold) {
        return new ThresholdRule("rule", "rule", e -> true, SecurityEvent::host,
                threshold, Duration.ofMinutes(1), Severity.HIGH, "alert");
    }
    private static SecurityEvent event(int i) {
        return new SecurityEvent("event-" + i, Instant.ofEpochSecond(i), "test", "host", "event",
                Map.of("tenant_id", "test"), Severity.INFO);
    }
    private static final class Sink implements BatchEventAlertSink {
        final List<List<DetectionResult>> batches = new ArrayList<>();
        volatile boolean fail;
        ThreadLocal<String> context;
        @Override public boolean supportsAtomicBatch() { return true; }
        @Override public boolean ownsFencedTransaction() { return true; }
        @Override public void publishBatch(List<Entry> entries) {
            if (context != null) assertEquals(entries.getLast().result().event().id(), context.get());
            if (fail) throw new IllegalStateException("transaction rolled back");
            batches.add(entries.stream().map(Entry::result).toList());
        }
        @Override public void publish(SecurityEvent event, List<Alert> alerts) { throw new AssertionError("batch path required"); }
        @Override public void close() { }
    }
}
