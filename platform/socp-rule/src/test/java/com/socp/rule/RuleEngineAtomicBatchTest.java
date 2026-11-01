package com.socp.rule;

import com.socp.rule.engine.BatchEventAlertSink;
import com.socp.rule.engine.DetectionResult;
import com.socp.rule.engine.RuleEngine;
import com.socp.rule.engine.RuleExecutionScope;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class RuleEngineAtomicBatchTest {
    @Test void oversizedAtomicUndoRollsBackBeforeAnySinkOrPositionCallback() throws Exception {
        ThresholdRule delegate = rule();
        com.socp.rule.state.StatefulRule oversized = new com.socp.rule.state.StatefulRule() {
            public String id() { return delegate.id(); }
            public String name() { return delegate.name(); }
            public void accept(SecurityEvent event) { delegate.accept(event); }
            public List<Alert> drain() { return delegate.drain(); }
            public String stateVersion() { return delegate.stateVersion(); }
            public byte[] snapshotState() { return delegate.snapshotState(); }
            public void restoreState(byte[] bytes) { delegate.restoreState(bytes); }
            public void close() { delegate.close(); }
            public com.socp.rule.state.StateMutation beginMutation() {
                var mutation = delegate.beginMutation();
                return new com.socp.rule.state.StateMutation() {
                    public DetectionResult.StateChange change(String ruleId) { return mutation.change(ruleId); }
                    public void rollback() { mutation.rollback(); }
                    public void detach() { mutation.detach(); }
                    public long retainedBytes() { return 4L * 1024 * 1024 + 1; }
                    public void close() { mutation.close(); }
                };
            }
        };
        Sink sink = new Sink();
        AtomicBoolean positioned = new AtomicBoolean();
        try (var engine = new RuleEngine(List.of(oversized), List.of(sink))) {
            engine.start();
            byte[] before = oversized.snapshotState();
            var failure = assertThrows(ExecutionException.class, () -> engine.ingestBatchAndAwait(
                    batch(0, 3), () -> positioned.set(true), null).get(5, TimeUnit.SECONDS));
            assertTrue(failure.getCause().getMessage().contains("undo budget"));
            assertTrue(sink.committed.isEmpty());
            assertFalse(positioned.get());
            assertArrayEquals(before, oversized.snapshotState());
        }
    }

    private static SecurityEvent event(int n) {
        return new SecurityEvent("event-" + n, Instant.parse("2026-10-29T00:00:00Z"),
                "auth", "host", "message", Map.of("tenant_id", "tenant-a", "entity", "same"), Severity.INFO);
    }
    private static ThresholdRule rule() {
        return new ThresholdRule("threshold", "threshold", e -> true, e -> e.get("entity"),
                3, Duration.ofMinutes(5), Severity.HIGH, "threshold");
    }
    private static List<RuleEngine.BatchEvent> batch(int start, int end) {
        return java.util.stream.IntStream.range(start, end).mapToObj(n -> new RuleEngine.BatchEvent(
                event(n), new DetectionResult.InputPosition("events", 0, n), RuleExecutionScope.NOOP)).toList();
    }
    private static class Sink implements BatchEventAlertSink {
        final List<DetectionResult> committed = new ArrayList<>();
        boolean fail;
        public boolean supportsAtomicBatch() { return true; }
        public void publishBatch(List<DetectionResult> results, List<RuleExecutionScope> scopes, Runnable guard) {
            if (guard != null) guard.run();
            if (fail) throw new IllegalStateException("transaction rolled back");
            if (guard != null) guard.run();
            committed.addAll(results);
        }
        public void publish(SecurityEvent event, List<Alert> alerts) { }
        public void publish(DetectionResult result, Runnable guard) { committed.add(result); }
        public void close() { }
    }

    @Test void failedBatchRestoresRulesAndSuppressionAndRetryProducesTheSameEvidence() throws Exception {
        Sink sink = new Sink(); sink.fail = true;
        try (var suppressor = new Suppressor(Duration.ofMinutes(1));
             var engine = new RuleEngine(List.of(rule()), List.of(sink), suppressor)) {
            engine.start();
            byte[] before = engine.snapshotStates().get("threshold").serializedState();
            AtomicBoolean positioned = new AtomicBoolean();
            assertThrows(ExecutionException.class, () -> engine.ingestBatchAndAwait(batch(0, RuleEngine.MAX_BATCH_EVENTS),
                    () -> positioned.set(true), null).get(5, TimeUnit.SECONDS));
            assertFalse(positioned.get());
            assertTrue(sink.committed.isEmpty());
            assertArrayEquals(before, engine.snapshotStates().get("threshold").serializedState());
            sink.fail = false;
            engine.ingestBatchAndAwait(batch(0, RuleEngine.MAX_BATCH_EVENTS), () -> positioned.set(true), null).get(5, TimeUnit.SECONDS);
            assertTrue(positioned.get());
            assertEquals(RuleEngine.MAX_BATCH_EVENTS, sink.committed.size());
            var alerts = sink.committed.stream().flatMap(r -> r.alerts().stream()).toList();
            assertEquals(1, alerts.size(), "second alert in the same suppression window stays suppressed");
            assertEquals(List.of("event-0", "event-1", "event-2"),
                    alerts.getFirst().evidence().stream().map(SecurityEvent::id).toList());
            assertEquals(java.util.stream.LongStream.range(0, RuleEngine.MAX_BATCH_EVENTS).boxed().toList(),
                    sink.committed.stream().map(r -> r.inputPosition().offset()).toList());
        }
    }

    @Test void slowBatchDoesNotBlockUnrelatedTenantOrAlertFreeBatch() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        Sink blocked = new Sink() {
            public void publishBatch(List<DetectionResult> results, List<RuleExecutionScope> scopes, Runnable guard) {
                entered.countDown();
                try { if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("test deadline"); }
                catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new IllegalStateException(ex); }
                super.publishBatch(results, scopes, guard);
            }
        };
        Sink independent = new Sink();
        Sink empty = new Sink();
        try (var suppressor = new Suppressor(Duration.ofMinutes(1));
             var first = new RuleEngine(List.of(rule()), List.of(blocked), suppressor);
             var other = new RuleEngine(List.of(rule()), List.of(independent), suppressor);
             var quiet = new RuleEngine(List.of(), List.of(empty), suppressor)) {
            first.start(); other.start(); quiet.start();
            var pending = first.ingestBatchAndAwait(batch(0, 3), null, null);
            try {
                assertTrue(entered.await(2, TimeUnit.SECONDS));
                var otherTenant = batch(0, 3).stream().map(input -> {
                    var e = input.event();
                    var tenantEvent = new SecurityEvent(e.id(), e.timestamp(), e.source(), e.host(), e.raw(),
                            Map.of("tenant_id", "tenant-b", "entity", "same"), e.severity());
                    return new RuleEngine.BatchEvent(tenantEvent, input.position(), input.scope());
                }).toList();
                other.ingestBatchAndAwait(otherTenant, null, null).get(2, TimeUnit.SECONDS);
                quiet.ingestBatchAndAwait(batch(10, 13), null, null).get(2, TimeUnit.SECONDS);
                assertFalse(pending.isDone());
                assertEquals(1, independent.committed.stream().mapToInt(r -> r.alerts().size()).sum());
                assertEquals(3, empty.committed.size());
            } finally { release.countDown(); }
            pending.get(2, TimeUnit.SECONDS);
        }
    }

    @Test void failureAfterDurableCommitDoesNotUndoAlreadyCommittedRuleState() throws Exception {
        Sink sink = new Sink();
        try (var engine = new RuleEngine(List.of(rule()), List.of(sink))) {
            engine.start();
            assertThrows(ExecutionException.class, () -> engine.ingestBatchAndAwait(batch(0, 4),
                    () -> { throw new IllegalStateException("position observation lost ownership"); }, null)
                    .get(5, TimeUnit.SECONDS));
            assertEquals(4, sink.committed.size());
            engine.ingestAndAwait(event(4)).get(5, TimeUnit.SECONDS);
            engine.ingestAndAwait(event(5)).get(5, TimeUnit.SECONDS);
            var alerts = sink.committed.stream().flatMap(r -> r.alerts().stream()).toList();
            assertEquals(2, alerts.size());
            assertEquals(List.of("event-3", "event-4", "event-5"),
                    alerts.getLast().evidence().stream().map(SecurityEvent::id).toList());
        }
    }

    @Test void batchCompletionWaitsForAtomicSinkAndInvalidBatchesNeverEnterQueue() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        Sink sink = new Sink() {
            public void publishBatch(List<DetectionResult> results, List<RuleExecutionScope> scopes, Runnable guard) {
                entered.countDown();
                try { if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("test deadline"); }
                catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new IllegalStateException(ex); }
                super.publishBatch(results, scopes, guard);
            }
        };
        try (var engine = new RuleEngine(List.of(rule()), List.of(sink))) {
            engine.start();
            assertThrows(IllegalArgumentException.class, () -> engine.ingestBatchAndAwait(
                    batch(0, RuleEngine.MAX_BATCH_EVENTS + 1), null, null));
            assertThrows(IllegalArgumentException.class, () -> engine.ingestBatchAndAwait(
                    List.of(batch(0, 1).getFirst(), batch(0, 1).getFirst()), null, null));
            var future = engine.ingestBatchAndAwait(batch(0, RuleEngine.MAX_BATCH_EVENTS), null, null);
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS)); assertFalse(future.isDone());
            } finally { release.countDown(); }
            future.get(5, TimeUnit.SECONDS);
            assertEquals(RuleEngine.MAX_BATCH_EVENTS, sink.committed.size());
            assertTrue(engine.awaitIdle(5000));
            assertEquals(0.0, engine.queueLoad());
        }
    }
}
