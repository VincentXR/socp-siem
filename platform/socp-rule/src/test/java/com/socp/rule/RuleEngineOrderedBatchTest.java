package com.socp.rule;

import com.socp.rule.engine.BatchEventAlertSink;
import com.socp.rule.engine.DetectionResult;
import com.socp.rule.engine.EventAlertSink;
import com.socp.rule.engine.OrderedCommitGuard;
import com.socp.rule.engine.RuleEngine;
import com.socp.rule.engine.RuleProcessingObserver;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class RuleEngineOrderedBatchTest {
    @Test
    void failedCommitCancelsEveryAffectedGroupBeforeCompletionAndFreshRetrySucceeds() throws Exception {
        Sink sink = new Sink();
        sink.failAt = 1;
        var rule = rule(1_000);
        byte[] before = rule.snapshotState();
        var first = new RuleEngine.OrderedBatch();
        var second = new RuleEngine.OrderedBatch();
        var position = new AtomicLong(-1);
        try (RuleEngine engine = new RuleEngine(List.of(rule), List.of(sink))) {
            List<CompletableFuture<Void>> pending = new ArrayList<>();
            for (int i = 0; i < 20; i++) pending.add(submit(engine, i, i % 2 == 0 ? first : second, position));
            AtomicBoolean bothFailedBeforeCallback = new AtomicBoolean();
            pending.getFirst().whenComplete((ignored, failure) -> bothFailedBeforeCallback.set(
                    first.failure() == sink.failure && second.failure() == sink.failure));
            engine.start();
            assertFailed(pending, sink.failure);
            assertTrue(bothFailedBeforeCallback.get());
            assertEquals(1, sink.calls.get(), "queued successors must not reach a second transaction");
            assertEquals(sink.attemptedItems, engine.eventCount(), "cancelled successors must not evaluate rules");
            assertArrayEquals(before, rule.snapshotState());
            assertEquals(-1, position.get());

            sink.failAt = 0;
            var retry = new RuleEngine.OrderedBatch();
            List<CompletableFuture<Void>> retried = new ArrayList<>();
            for (int i = 0; i < 20; i++) retried.add(submit(engine, i, retry, position));
            await(retried);
            assertEquals(java.util.stream.IntStream.range(0, 20).mapToObj(i -> "event-" + i).toList(), sink.ids());
            assertEquals(19, position.get());
        }
    }

    @Test
    void committedCandidatePrefixSurvivesLaterFailureAndSuffixRetry() throws Exception {
        Sink sink = new Sink();
        sink.failAt = 2;
        var rule = rule(1);
        var expectedPrefix = rule(1);
        expectedPrefix.accept(event(0));
        byte[] prefixState = expectedPrefix.snapshotState();
        expectedPrefix.close();
        var group = new RuleEngine.OrderedBatch();
        var position = new AtomicLong(-1);
        try (RuleEngine engine = new RuleEngine(List.of(rule), List.of(sink))) {
            List<CompletableFuture<Void>> pending = new ArrayList<>();
            for (int i = 0; i < 7; i++) pending.add(submit(engine, i, group, position));
            engine.start();
            pending.getFirst().get(5, TimeUnit.SECONDS);
            assertFailed(pending.subList(1, pending.size()), sink.failure);
            assertEquals(List.of("event-0"), sink.ids());
            assertEquals(0, position.get());
            assertArrayEquals(prefixState, rule.snapshotState());

            sink.failAt = 0;
            var retry = new RuleEngine.OrderedBatch();
            List<CompletableFuture<Void>> retried = new ArrayList<>();
            for (int i = 1; i < 7; i++) retried.add(submit(engine, i, retry, position));
            await(retried);
            assertEquals(java.util.stream.IntStream.range(0, 7).mapToObj(i -> "event-" + i).toList(), sink.ids());
            assertEquals(6, position.get());
        }
    }

    @Test
    void admissionCancellationDuringPreparationRollsBackBeforePublishing() throws Exception {
        Sink sink = new Sink();
        var group = new RuleEngine.OrderedBatch();
        var failure = new IllegalStateException("later admission rejected");
        var rule = rule(1_000);
        byte[] before = rule.snapshotState();
        RuleProcessingObserver observer = new RuleProcessingObserver() {
            @Override public void evaluationCompleted(SecurityEvent event, int alerts) { group.fail(failure); }
        };
        try (RuleEngine engine = new RuleEngine(List.of(rule), List.of(sink), null, observer)) {
            List<CompletableFuture<Void>> pending = new ArrayList<>();
            for (int i = 0; i < 4; i++) pending.add(submit(engine, i, group, new AtomicLong(-1)));
            engine.start();
            assertFailed(pending, failure);
            assertEquals(0, sink.calls.get());
            assertEquals(1, engine.eventCount());
            assertArrayEquals(before, rule.snapshotState());
        }
    }

    @Test
    void admissionCancellationCannotRetractAnAlreadyStartedSuccessfulCommit() throws Exception {
        Sink sink = new Sink();
        CountDownLatch committing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        sink.beforeCommit = () -> {
            committing.countDown();
            try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
            catch (InterruptedException interrupted) { throw new IllegalStateException(interrupted); }
        };
        var group = new RuleEngine.OrderedBatch();
        var failure = new IllegalStateException("later admission rejected");
        var position = new AtomicLong(-1);
        try (RuleEngine engine = new RuleEngine(List.of(rule(1)), List.of(sink))) {
            List<CompletableFuture<Void>> pending = new ArrayList<>();
            for (int i = 0; i < 4; i++) pending.add(submit(engine, i, group, position));
            engine.start();
            try {
                assertTrue(committing.await(5, TimeUnit.SECONDS));
                group.fail(failure);
            } finally { release.countDown(); }
            pending.getFirst().get(5, TimeUnit.SECONDS);
            assertFailed(pending.subList(1, pending.size()), failure);
            assertEquals(List.of("event-0"), sink.ids());
            assertEquals(0, position.get());
        }
    }

    @Test
    void nonBatchSinkFailureAlsoBlocksOrderedSuccessors() throws Exception {
        var failure = new IllegalStateException("legacy sink unavailable");
        var calls = new AtomicInteger();
        EventAlertSink sink = new EventAlertSink() {
            @Override public void publish(DetectionResult result, Runnable guard) {
                calls.incrementAndGet();
                throw failure;
            }
            @Override public void publish(SecurityEvent event, List<Alert> alerts) { fail("result path required"); }
            @Override public void close() { }
        };
        var group = new RuleEngine.OrderedBatch();
        var rule = rule(1_000);
        byte[] before = rule.snapshotState();
        try (RuleEngine engine = new RuleEngine(List.of(rule), List.of(sink))) {
            List<CompletableFuture<Void>> pending = new ArrayList<>();
            for (int i = 0; i < 3; i++) pending.add(submit(engine, i, group, new AtomicLong(-1)));
            engine.start();
            assertFailed(pending, failure);
            assertEquals(1, calls.get());
            assertArrayEquals(before, rule.snapshotState());
        }
    }

    @Test
    void aTokenCannotSpanEnginesOrAdmitMoreThan64Events() throws Exception {
        var group = new RuleEngine.OrderedBatch();
        try (RuleEngine first = new RuleEngine(List.of(rule(1_000)), List.of(new Sink()));
             RuleEngine second = new RuleEngine(List.of(rule(1_000)), List.of(new Sink()))) {
            var queued = submit(first, 0, group, new AtomicLong(-1));
            var rejected = submit(second, 1, group, new AtomicLong(-1));
            assertInstanceOf(IllegalArgumentException.class, group.failure());
            first.start();
            second.start();
            assertFailed(List.of(queued, rejected), group.failure());
            assertEquals(0, first.eventCount());
            assertEquals(0, second.eventCount());
        }
        group = new RuleEngine.OrderedBatch();
        try (RuleEngine engine = new RuleEngine(List.of(rule(1_000)), List.of(new Sink()))) {
            List<CompletableFuture<Void>> pending = new ArrayList<>();
            for (int i = 0; i < 65; i++) pending.add(submit(engine, i, group, new AtomicLong(-1)));
            assertInstanceOf(IllegalArgumentException.class, group.failure());
            engine.start();
            assertFailed(pending, group.failure());
            assertEquals(0, engine.eventCount());
        }
    }

    private static CompletableFuture<Void> submit(RuleEngine engine, int offset,
                                                   RuleEngine.OrderedBatch group, AtomicLong position) {
        return engine.ingestAndAwait(event(offset), new DetectionResult.InputPosition("events", 0, offset),
                () -> position.set(offset), new OrderedCommitGuard("unit", () -> { }), group);
    }

    private static void await(List<CompletableFuture<Void>> futures) throws Exception {
        CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).get(5, TimeUnit.SECONDS);
    }

    private static void assertFailed(List<CompletableFuture<Void>> futures, Throwable failure) throws Exception {
        CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
                .handle((ignored, cause) -> null).get(5, TimeUnit.SECONDS);
        for (var future : futures) assertSame(failure, assertThrows(CompletionException.class, future::join).getCause());
    }

    private static ThresholdRule rule(int threshold) {
        return new ThresholdRule("rule", "rule", event -> true, SecurityEvent::host,
                threshold, Duration.ofMinutes(5), Severity.HIGH, "alert");
    }

    private static SecurityEvent event(int i) {
        return new SecurityEvent("event-" + i, Instant.ofEpochSecond(i), "test", "host", "event",
                Map.of("tenant_id", "test"), Severity.INFO);
    }

    private static final class Sink implements BatchEventAlertSink {
        final AtomicInteger calls = new AtomicInteger();
        final IllegalStateException failure = new IllegalStateException("transaction rolled back");
        final List<DetectionResult> committed = new ArrayList<>();
        volatile int failAt;
        long attemptedItems;
        Runnable beforeCommit = () -> { };
        @Override public boolean supportsAtomicBatch() { return true; }
        @Override public boolean ownsFencedTransaction() { return true; }
        @Override public void publishBatch(List<Entry> entries) {
            attemptedItems += entries.size();
            int attempt = calls.incrementAndGet();
            beforeCommit.run();
            if (attempt == failAt) throw failure;
            committed.addAll(entries.stream().map(Entry::result).toList());
        }
        List<String> ids() { return committed.stream().map(result -> result.event().id()).toList(); }
        @Override public void publish(SecurityEvent event, List<Alert> alerts) { fail("batch path required"); }
        @Override public void close() { }
    }
}
