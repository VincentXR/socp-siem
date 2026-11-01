package com.socp.detect.web.service;

import com.socp.detect.web.engine.AlertForwarder;
import com.socp.detect.web.engine.RecentAlertSink;
import com.socp.detect.web.persistence.store.DetectionAlertOutboxService;
import com.socp.detect.web.persistence.store.DetectionEventClaim;
import com.socp.detect.web.persistence.store.InMemoryDetectionStateStore;
import com.socp.detect.web.persistence.store.RuleSpecStore;
import com.socp.platform.tenant.context.TenantContext;
import com.socp.rule.engine.BatchEventAlertSink;
import com.socp.rule.engine.RuleProcessingObserver;
import com.socp.rule.model.SecurityEvent;
import com.socp.rule.model.Severity;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/** Service admission and real sink wiring; database atomicity is verified in the PostgreSQL suite. */
class DetectEngineBatchTest {
    @ParameterizedTest
    @ValueSource(strings = {"assignment", "batch-interrupt", "single-interrupt"})
    void cancelledAdmissionWaitingForReplacementCannotEnterAnEngine(String cancellation) throws Exception {
        var rules = emptyRules();
        var journal = new InMemoryDetectionStateStore();
        var publications = new AtomicInteger();
        var forwarder = new AlertForwarder(rules, mock(EntityRiskStore.class), mock(DetectionAlertOutboxService.class), journal) {
            @Override public void forwardBatch(List<BatchEventAlertSink.Entry> entries) {
                publications.incrementAndGet();
                super.forwardBatch(entries);
            }
        };
        var service = new DetectEngineService(rules, new RecentAlertSink(10, forwarder, null), journal);
        var lifecycle = ReflectionTestUtils.getField(service, "lifecycle");
        var lock = (java.util.concurrent.locks.ReentrantReadWriteLock)
                ReflectionTestUtils.getField(lifecycle, "engineLifecycle");
        var active = new AtomicBoolean(true);
        var interruptedOnReturn = new AtomicBoolean();
        var admitted = new CompletableFuture<List<CompletableFuture<Void>>>();
        var input = inputs(cancellation.equals("single-interrupt") ? 1 : 2);
        Thread submitter = null;
        try {
            service.start();
            input.forEach(row -> journal.claim(row.event(), row.partition(), row.offset(), "host"));
            lock.writeLock().lock();
            submitter = Thread.ofVirtual().start(() -> {
                try {
                    List<CompletableFuture<Void>> completions;
                    if (cancellation.equals("assignment")) {
                        completions = service.ingestOrderedBatchFromKafkaAndAwait(input, active::get);
                    } else if (cancellation.equals("batch-interrupt")) {
                        completions = service.ingestOrderedBatchFromKafkaAndAwait(input);
                    } else {
                        var row = input.getFirst();
                        completions = List.of(service.ingestFromKafkaAndAwait(row.event(), row.topic(), row.partition(), row.offset()));
                    }
                    interruptedOnReturn.set(Thread.currentThread().isInterrupted());
                    admitted.complete(completions);
                } catch (Throwable failure) { admitted.completeExceptionally(failure); }
            });
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (!lock.hasQueuedThread(submitter) && System.nanoTime() < deadline) Thread.sleep(1);
            assertTrue(lock.hasQueuedThread(submitter), "admission must wait for the generation lock");
            if (cancellation.equals("assignment")) active.set(false);
            else submitter.interrupt();
            lock.writeLock().unlock();

            var completions = admitted.get(5, TimeUnit.SECONDS);
            assertEquals(input.size(), completions.size());
            for (var completion : completions) assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> completion.get(2, TimeUnit.SECONDS));
            assertEquals(!cancellation.equals("assignment"), interruptedOnReturn.get());
            assertEquals(input.size(), journal.pendingCount());
            assertEquals(0, publications.get());
            verify(rules, never()).list("batch-tenant");
        } finally {
            if (lock.isWriteLockedByCurrentThread()) lock.writeLock().unlock();
            if (submitter != null) {
                submitter.interrupt();
                submitter.join(2_000);
            }
            service.stop();
            TenantContext.clear();
        }
    }

    @Test
    void assignmentRetiredDuringAdmissionKeepsTheOriginalDurablePrefixFuture() throws Exception {
        var rules = emptyRules();
        var journal = new InMemoryDetectionStateStore();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var publications = new AtomicInteger();
        var forwarder = new AlertForwarder(rules, mock(EntityRiskStore.class), mock(DetectionAlertOutboxService.class), journal) {
            @Override public void forwardBatch(List<BatchEventAlertSink.Entry> entries) {
                publications.incrementAndGet();
                entered.countDown();
                await(release);
                super.forwardBatch(entries);
            }
        };
        var service = new DetectEngineService(rules, new RecentAlertSink(10, forwarder, null), journal);
        var checks = new AtomicInteger();
        var input = inputs(3);
        try {
            service.start();
            input.forEach(row -> journal.claim(row.event(), row.partition(), row.offset(), "host"));
            var completions = service.ingestOrderedBatchFromKafkaAndAwait(input, () -> {
                if (checks.incrementAndGet() == 1) return true;
                await(entered);
                return false;
            });
            assertEquals(3, completions.size());
            assertFalse(completions.getFirst().isDone(), "retain the original in-flight transaction's future");
            for (var completion : completions.subList(1, 3)) {
                assertThrows(java.util.concurrent.ExecutionException.class, () -> completion.get(2, TimeUnit.SECONDS));
            }
            release.countDown();
            completions.getFirst().get(5, TimeUnit.SECONDS);
            assertEquals(1, publications.get());
            assertEquals(2, journal.pendingCount());
            assertEquals(DetectionEventClaim.COMPLETED, journal.claim(input.getFirst().event()));
        } finally {
            release.countDown();
            service.stop();
            TenantContext.clear();
        }
    }

    @Test
    void onePartitionCanSubmitMultipleEventsWithTenantAndTracePreserved() throws Exception {
        var rules = emptyRules();
        var journal = new InMemoryDetectionStateStore();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        List<List<String>> committed = new ArrayList<>();
        var traces = new ConcurrentHashMap<String, String>();
        var forwarder = new AlertForwarder(rules, mock(EntityRiskStore.class), mock(DetectionAlertOutboxService.class), journal) {
            @Override public void forwardBatch(List<BatchEventAlertSink.Entry> entries) {
                entered.countDown();
                await(release);
                super.forwardBatch(entries);
                committed.add(entries.stream().map(entry -> entry.result().event().id()).toList());
            }
        };
        var observer = new RuleProcessingObserver() {
            @Override public void evaluationCompleted(SecurityEvent event, int alerts) {
                assertEquals(event.requireTenantId(), TenantContext.require());
                traces.put(event.id(), String.valueOf(org.slf4j.MDC.get("traceId")));
            }
        };
        var service = new DetectEngineService(rules, new RecentAlertSink(10, forwarder, null), journal, observer, null);
        List<DetectEngineService.KafkaInput> inputs = inputs(32);
        try {
            service.start();
            inputs.forEach(input -> journal.claim(input.event(), input.partition(), input.offset(), "host"));
            List<CompletableFuture<Void>> completions;
            try (var caller = TenantContext.open("caller")) {
                completions = service.ingestOrderedBatchFromKafkaAndAwait(inputs);
                assertEquals("caller", TenantContext.require(), "batch tenant must not leak to the submitting caller");
            }
            assertEquals(32, completions.size());
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertEquals(32, journal.pendingCount(), "submission must not acknowledge records before sink completion");
            release.countDown();
            CompletableFuture.allOf(completions.toArray(CompletableFuture[]::new)).get(10, TimeUnit.SECONDS);
            assertEquals(0, journal.pendingCount());
            assertEquals(inputs.stream().map(input -> input.event().id()).toList(), committed.stream().flatMap(List::stream).toList());
            assertTrue(committed.stream().anyMatch(batch -> batch.size() > 1), "one partition must be able to fill an engine batch");
            for (int i = 0; i < inputs.size(); i++) {
                assertEquals(DetectionEventClaim.COMPLETED, journal.claim(inputs.get(i).event()));
                assertEquals(traceId(i), traces.get(inputs.get(i).event().id()));
            }
            verify(rules).list("batch-tenant");
            assertNull(TenantContext.get());
        } finally {
            release.countDown();
            service.stop();
            TenantContext.clear();
        }
    }

    @Test
    void rejectedCommitBlocksTheSubmittedSuffixAndAllowsOrderedReplay() throws Exception {
        var rules = emptyRules();
        var journal = new InMemoryDetectionStateStore();
        var release = new CountDownLatch(1);
        var fail = new AtomicBoolean(true);
        var publications = new AtomicInteger();
        var forwarder = new AlertForwarder(rules, mock(EntityRiskStore.class), mock(DetectionAlertOutboxService.class), journal) {
            @Override public void forwardBatch(List<BatchEventAlertSink.Entry> entries) {
                publications.incrementAndGet();
                await(release);
                if (fail.get()) throw new IllegalStateException("transaction rejected");
                super.forwardBatch(entries);
            }
        };
        var service = new DetectEngineService(rules, new RecentAlertSink(10, forwarder, null), journal);
        var inputs = inputs(32);
        try {
            service.start();
            inputs.forEach(input -> journal.claim(input.event(), input.partition(), input.offset(), "host"));
            var rejected = service.ingestOrderedBatchFromKafkaAndAwait(inputs);
            release.countDown();
            for (var completion : rejected) assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> completion.get(10, TimeUnit.SECONDS));
            assertEquals(1, publications.get(), "failed group successors must not publish past the gap");
            assertEquals(32, journal.pendingCount());
            fail.set(false);
            var replayed = service.ingestOrderedBatchFromKafkaAndAwait(inputs);
            CompletableFuture.allOf(replayed.toArray(CompletableFuture[]::new)).get(10, TimeUnit.SECONDS);
            assertEquals(0, journal.pendingCount());
        } finally {
            release.countDown();
            service.stop();
            TenantContext.clear();
        }
    }

    @Test
    void invalidGroupsAreRejectedBeforeAnyEngineAdmissionOrTenantChange() {
        var rules = mock(RuleSpecStore.class);
        var journal = new InMemoryDetectionStateStore();
        var service = new DetectEngineService(rules, new RecentAlertSink(10, null, null), journal);
        var input = inputs(2);
        var first = input.getFirst();
        var next = input.getLast();
        var foreign = new SecurityEvent("foreign", Instant.now(), "test", "host", "raw", Map.of("tenant_id", "other"), Severity.INFO);
        List<List<DetectEngineService.KafkaInput>> invalid = List.of(
                List.of(),
                List.of(first, new DetectEngineService.KafkaInput(foreign, first.topic(), first.partition(), next.offset())),
                List.of(first, new DetectEngineService.KafkaInput(next.event(), first.topic(), 3, next.offset())),
                List.of(first, new DetectEngineService.KafkaInput(next.event(), "other-topic", first.partition(), next.offset())),
                List.of(next, first),
                List.of(first, new DetectEngineService.KafkaInput(first.event(), first.topic(), first.partition(), next.offset())),
                inputs(65));
        try (var caller = TenantContext.open("caller")) {
            for (var batch : invalid) {
                assertThrows(IllegalArgumentException.class, () -> service.ingestOrderedBatchFromKafkaAndAwait(batch));
                assertEquals("caller", TenantContext.require());
            }
            assertEquals(0, journal.pendingCount());
            verifyNoInteractions(rules);
        } finally { service.stop(); }
    }

    private RuleSpecStore emptyRules() {
        var rules = mock(RuleSpecStore.class);
        when(rules.list(anyString())).thenAnswer(invocation -> {
            assertEquals(invocation.getArgument(0), TenantContext.require(), "fresh engine must load rules in its own tenant");
            return List.of();
        });
        return rules;
    }

    private List<DetectEngineService.KafkaInput> inputs(int count) {
        return java.util.stream.IntStream.range(0, count).mapToObj(index -> {
            var event = new SecurityEvent("batch-" + index, Instant.now(), "test", "host", "raw",
                    Map.of("tenant_id", "batch-tenant"), Severity.INFO);
            var headers = new RecordHeaders().add("traceparent",
                    ("00-" + traceId(index) + "-1111111111111111-01").getBytes(StandardCharsets.UTF_8));
            return new DetectEngineService.KafkaInput(event, "socp-events", 2, (long) index, headers);
        }).toList();
    }

    private static String traceId(int index) { return String.format("%032x", index + 1); }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("test coordination timed out");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }
}
