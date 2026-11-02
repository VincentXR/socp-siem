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
    @Test void queuedAtomicBatchesShareOneCommitAndPublishAllPositionsBeforeAnyCompletion() throws Exception {
        var commits = new java.util.concurrent.atomic.AtomicInteger();
        var positions = new java.util.concurrent.atomic.AtomicInteger();
        var guarded = new ArrayList<String>();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        Sink sink = new Sink() {
            @Override public void publishBatch(List<BatchEventAlertSink.Entry> entries) {
                commits.incrementAndGet();
                com.socp.rule.engine.OrderedCommitGuard.runAll(entries.stream().map(Entry::guard)
                        .filter(java.util.Objects::nonNull)
                        .map(com.socp.rule.engine.OrderedCommitGuard.class::cast).toList());
                entered.countDown();
                try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
                catch (InterruptedException interrupted) { throw new IllegalStateException(interrupted); }
                committed.addAll(entries.stream().map(Entry::result).toList());
            }
        };
        try (var engine = new RuleEngine(List.of(), List.of(sink))) {
            var first = engine.ingestBatchAndAwait(batch(0, 4), positions::incrementAndGet,
                    new com.socp.rule.engine.OrderedCommitGuard("b", () -> guarded.add("b")));
            var second = engine.ingestBatchAndAwait(batch(4, 8), positions::incrementAndGet,
                    new com.socp.rule.engine.OrderedCommitGuard("a", () -> guarded.add("a")));
            var observed = new ArrayList<Integer>();
            var firstObserved = first.thenRun(() -> observed.add(positions.get()));
            var secondObserved = second.thenRun(() -> observed.add(positions.get()));
            engine.start();
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                assertFalse(first.isDone());
                assertFalse(second.isDone());
            } finally { release.countDown(); }
            java.util.concurrent.CompletableFuture.allOf(firstObserved, secondObserved).get(5, TimeUnit.SECONDS);
            assertEquals(1, commits.get());
            assertEquals(List.of("a", "b"), guarded);
            assertEquals(List.of(2, 2), observed);
            assertEquals(java.util.stream.IntStream.range(0, 8).mapToObj(n -> "event-" + n).toList(),
                    sink.committed.stream().map(result -> result.event().id()).toList());
            assertTrue(engine.awaitIdle(5000));
            assertEquals(0.0, engine.queueLoad());
        }
    }

    @Test void coalescedFailureRollsBackEveryCallerAndPreservesSuppressionForRetry() throws Exception {
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        Sink sink = new Sink() {
            @Override public void publishBatch(List<BatchEventAlertSink.Entry> entries) {
                calls.incrementAndGet();
                super.publishBatch(entries);
            }
        };
        sink.fail = true;
        var positions = new java.util.concurrent.atomic.AtomicInteger();
        try (var suppressor = new Suppressor(Duration.ofMinutes(1));
             var engine = new RuleEngine(List.of(rule()), List.of(sink), suppressor)) {
            byte[] before = engine.snapshotStates().get("threshold").serializedState();
            var first = engine.ingestBatchAndAwait(batch(0, 3), positions::incrementAndGet, null);
            var second = engine.ingestBatchAndAwait(batch(3, 6), positions::incrementAndGet, null);
            engine.start();
            assertThrows(ExecutionException.class, () -> first.get(5, TimeUnit.SECONDS));
            assertThrows(ExecutionException.class, () -> second.get(5, TimeUnit.SECONDS));
            assertEquals(1, calls.get());
            assertEquals(0, positions.get());
            assertTrue(sink.committed.isEmpty());
            assertArrayEquals(before, engine.snapshotStates().get("threshold").serializedState());
            sink.fail = false;
            engine.ingestBatchAndAwait(batch(0, 6), positions::incrementAndGet, null).get(5, TimeUnit.SECONDS);
            var alerts = sink.committed.stream().flatMap(result -> result.alerts().stream()).toList();
            assertEquals(1, alerts.size());
            assertEquals(List.of("event-0", "event-1", "event-2"),
                    alerts.getFirst().evidence().stream().map(SecurityEvent::id).toList());
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"32,5,128,32", "1,65,64,1"})
    void coalescingRetainsEventAndGuardLimitsWithoutSplittingOriginalBatches(
            int batchSize, int count, int firstSize, int secondSize) throws Exception {
        var sizes = new ArrayList<Integer>();
        Sink sink = new Sink() {
            @Override public void publishBatch(List<BatchEventAlertSink.Entry> entries) {
                sizes.add(entries.size());
                com.socp.rule.engine.OrderedCommitGuard.runAll(entries.stream().map(Entry::guard)
                        .filter(java.util.Objects::nonNull)
                        .map(com.socp.rule.engine.OrderedCommitGuard.class::cast).toList());
                committed.addAll(entries.stream().map(Entry::result).toList());
            }
        };
        try (var engine = new RuleEngine(List.of(), List.of(sink))) {
            var futures = new ArrayList<java.util.concurrent.CompletableFuture<Void>>();
            for (int i = 0; i < count; i++) futures.add(engine.ingestBatchAndAwait(
                    batch(i * batchSize, (i + 1) * batchSize), null,
                    new com.socp.rule.engine.OrderedCommitGuard("owner-" + i, () -> { })));
            engine.start();
            java.util.concurrent.CompletableFuture.allOf(futures.toArray(java.util.concurrent.CompletableFuture[]::new))
                    .get(5, TimeUnit.SECONDS);
            assertEquals(List.of(firstSize, secondSize), sizes);
            assertEquals(count * batchSize, sink.committed.size());
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"tenant", "identity", "guard"})
    void incompatibleAtomicBatchesRetainSeparateCommitBoundaries(String boundary) throws Exception {
        var sizes = new ArrayList<Integer>();
        Sink sink = new Sink() {
            @Override public void publishBatch(List<BatchEventAlertSink.Entry> entries) {
                sizes.add(entries.size());
                super.publishBatch(entries);
            }
        };
        var next = new ArrayList<>(batch(4, 8));
        if (boundary.equals("identity")) next.set(0, batch(0, 1).getFirst());
        if (boundary.equals("tenant")) next.replaceAll(input -> {
            var e = input.event();
            return new RuleEngine.BatchEvent(new SecurityEvent(e.id(), e.timestamp(), e.source(), e.host(), e.raw(),
                    Map.of("tenant_id", "tenant-b"), e.severity()), input.position(), input.scope());
        });
        try (var engine = new RuleEngine(List.of(), List.of(sink))) {
            var first = engine.ingestBatchAndAwait(batch(0, 4), null, null);
            var second = engine.ingestBatchAndAwait(next, null, boundary.equals("guard") ? () -> { } : null);
            engine.start();
            java.util.concurrent.CompletableFuture.allOf(first, second).get(5, TimeUnit.SECONDS);
            assertEquals(List.of(4, 4), sizes);
        }
    }

    @Test void combinedUndoOverflowRetriesOriginalBatchesAfterRestoringAllState() throws Exception {
        var tracked = withUndoBudget(rule(), 3L * 1024 * 1024);
        Sink sink = new Sink();
        try (var engine = new RuleEngine(List.of(tracked), List.of(sink))) {
            var first = engine.ingestBatchAndAwait(batch(0, 1), null, null);
            var second = engine.ingestBatchAndAwait(batch(1, 2), null, null);
            engine.start();
            java.util.concurrent.CompletableFuture.allOf(first, second).get(5, TimeUnit.SECONDS);
            engine.ingestBatchAndAwait(batch(2, 3), null, null).get(5, TimeUnit.SECONDS);
            assertEquals(3, sink.committed.size());
            var alerts = sink.committed.stream().flatMap(result -> result.alerts().stream()).toList();
            assertEquals(1, alerts.size());
            assertEquals(List.of("event-0", "event-1", "event-2"),
                    alerts.getFirst().evidence().stream().map(SecurityEvent::id).toList());
        }
    }

    private static com.socp.rule.state.StatefulRule withUndoBudget(ThresholdRule delegate, long bytes) {
        return new com.socp.rule.state.StatefulRule() {
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
                    public long retainedBytes() { return bytes; }
                    public void close() { mutation.close(); }
                };
            }
        };
    }

    @Test void oversizedAtomicUndoRollsBackBeforeAnySinkOrPositionCallback() throws Exception {
        var oversized = withUndoBudget(rule(), 4L * 1024 * 1024 + 1);
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
            var first = engine.ingestBatchAndAwait(batch(0, 3),
                    () -> { throw new IllegalStateException("position observation lost ownership"); }, null);
            var second = engine.ingestBatchAndAwait(batch(3, 4), null, null);
            engine.start();
            assertThrows(ExecutionException.class, () -> first.get(5, TimeUnit.SECONDS));
            assertThrows(ExecutionException.class, () -> second.get(5, TimeUnit.SECONDS));
            assertEquals(4, sink.committed.size());
            assertThrows(IllegalStateException.class, engine::snapshotStates,
                    "an incomplete combined position vector must not become a checkpoint");
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
