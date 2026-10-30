package com.socp.detect.web.engine;

import com.socp.detect.web.persistence.store.DetectionEventClaim;
import com.socp.detect.web.persistence.store.DetectionStateStore;
import com.socp.detect.web.service.DetectEngineService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@Timeout(15)
class KafkaEventBackpressureTest {
    private static final TopicPartition FIRST = new TopicPartition("events", 0);
    private static final TopicPartition SECOND = new TopicPartition("events", 1);
    private static final TopicPartition IDLE = new TopicPartition("events", 2);

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void processBudgetPausesEveryAssignmentAndDrainsOneReturnedPollLosslessly(boolean byteLimited) throws Exception {
        var store = mock(DetectionStateStore.class);
        var engine = mock(DetectEngineService.class);
        when(store.supportsBatchClaims()).thenReturn(true);
        when(engine.completesJournal(store)).thenReturn(true);
        when(engine.batchKey(any())).thenReturn(new DetectEngineService.BatchKey("tenant-a", 0));
        when(store.claimBatch(any())).thenAnswer(call ->
                java.util.Collections.nCopies(((List<?>) call.getArgument(0)).size(), DetectionEventClaim.NEW));
        when(engine.ingestBatchFromKafkaAndAwait(anyList(), any())).thenAnswer(call ->
                java.util.Collections.nCopies(((List<?>) call.getArgument(0)).size(), CompletableFuture.completedFuture(null)));
        when(engine.ingestFromKafkaAndAwait(any(), anyString(), any(), any()))
                .thenReturn(CompletableFuture.completedFuture(null));
        var consumer = new KafkaEventConsumer(engine, store);
        ReflectionTestUtils.setField(consumer, "maxPendingBytes", byteLimited ? 16_384L : 1_048_576L);
        ReflectionTestUtils.setField(consumer, "maxPendingRecords", byteLimited ? 4096L : 64L);
        var kafka = kafka(Set.of(FIRST, SECOND, IDLE));
        var releaseFirst = blockLane(consumer, FIRST);
        var releaseSecond = blockLane(consumer, SECOND);
        try {
            dispatch(consumer, kafka, Map.of(FIRST, records(FIRST, 0, 2), SECOND, records(SECOND, 0, 1)));
            pauseState(consumer, kafka);
            verify(kafka, never()).pause(any());
            long beforeBytes = aggregate(consumer, "totalPendingBytes");

            // Kafka already returned this entire 200-record poll before admission
            // crossed its limit. Every record remains reserved and ordered.
            dispatch(consumer, kafka, Map.of(FIRST, records(FIRST, 2, 100), SECOND, records(SECOND, 1, 100)));
            assertEquals(203, aggregate(consumer, "totalPendingRecords"));
            assertTrue(aggregate(consumer, "totalPendingBytes") > beforeBytes);
            if (byteLimited) assertTrue(aggregate(consumer, "totalPendingBytes") > 16_384L);
            else assertTrue(aggregate(consumer, "totalPendingRecords") <= 64 + 200);
            pauseState(consumer, kafka);
            verify(kafka).pause(Set.of(FIRST, SECOND, IDLE));
            for (int i = 0; i < 3; i++) {
                ReflectionTestUtils.invokeMethod(consumer, "drainDeferred", kafka);
                pauseState(consumer, kafka);
                assertEquals(203, aggregate(consumer, "totalPendingRecords"));
            }
            verify(kafka, never()).resume(any());

            releaseFirst.complete(null);
            drainLane(consumer, FIRST);
            assertEquals(101, aggregate(consumer, "totalPendingRecords"));
            pauseState(consumer, kafka);
            verify(kafka, never()).resume(any());
            releaseSecond.complete(null);
            drainLane(consumer, SECOND);
            ReflectionTestUtils.invokeMethod(consumer, "drainDeferred", kafka);
            pauseState(consumer, kafka);
            verify(kafka).resume(Set.of(FIRST, SECOND, IDLE));
            assertEquals(0, aggregate(consumer, "totalPendingBytes"));
            assertEquals(0, aggregate(consumer, "totalPendingRecords"));
            java.util.Queue<?> completions = (java.util.Queue<?>) ReflectionTestUtils.getField(consumer, "completions");
            assertEquals(203, completions.size());
        } finally {
            releaseFirst.complete(null);
            releaseSecond.complete(null);
            consumer.stop();
        }
    }

    @Test
    void movingDeferredWorkDoesNotResumeAnOverBudgetPartitionOrClearARetryPause() throws Exception {
        var consumer = new KafkaEventConsumer(mock(DetectEngineService.class));
        ReflectionTestUtils.setField(consumer, "partitionMaxPendingBytes", 1_000L);
        var kafka = kafka(Set.of(FIRST));
        var releaseLane = blockLane(consumer, FIRST);
        var secondStarted = new CountDownLatch(1);
        var releaseSecond = new CompletableFuture<Void>();
        try {
            reserve(consumer, FIRST, () -> { }, 600L);
            reserve(consumer, FIRST, () -> { secondStarted.countDown(); releaseSecond.join(); }, 600L);
            pauseState(consumer, kafka);
            verify(kafka).pause(Set.of(FIRST));
            ReflectionTestUtils.invokeMethod(consumer, "drainDeferred", kafka);
            pauseState(consumer, kafka);
            assertEquals(1_200, aggregate(consumer, "totalPendingBytes"));
            verify(kafka, never()).resume(any());

            releaseLane.complete(null);
            assertTrue(secondStarted.await(2, TimeUnit.SECONDS));
            assertEquals(600, aggregate(consumer, "totalPendingBytes"));
            ReflectionTestUtils.invokeMethod(consumer, "drainDeferred", kafka);
            pauseState(consumer, kafka);
            verify(kafka, never()).resume(any());

            var retry = (PartitionRetryState) ReflectionTestUtils.getField(consumer, "retryState");
            var lease = retry.acquire(FIRST);
            assertTrue(retry.block(lease, "dependency"));
            releaseSecond.complete(null);
            drainLane(consumer, FIRST);
            ReflectionTestUtils.invokeMethod(consumer, "drainDeferred", kafka);
            pauseState(consumer, kafka);
            assertEquals(0, aggregate(consumer, "totalPendingBytes"));
            verify(kafka, never()).resume(any());
            ReflectionTestUtils.invokeMethod(consumer, "clearRetryBlocked", lease, true);
            pauseState(consumer, kafka);
            verify(kafka).resume(Set.of(FIRST));
        } finally {
            releaseLane.complete(null);
            releaseSecond.complete(null);
            consumer.stop();
        }
    }

    @Test
    void revokedRunningWorkRetainsItsGlobalReservationAcrossReassignment() throws Exception {
        var consumer = new KafkaEventConsumer(mock(DetectEngineService.class));
        ReflectionTestUtils.setField(consumer, "maxPendingBytes", 1_000L);
        var kafka = kafka(Set.of(FIRST));
        var oldStarted = new CountDownLatch(1);
        var releaseOld = new CompletableFuture<Void>();
        var newStarted = new CountDownLatch(1);
        var releaseNew = new CompletableFuture<Void>();
        try {
            reserve(consumer, FIRST, () -> { oldStarted.countDown(); releaseOld.join(); }, 600L);
            assertTrue(oldStarted.await(2, TimeUnit.SECONDS));
            ThreadPoolExecutor original = ReflectionTestUtils.invokeMethod(consumer, "lane", FIRST.partition());
            consumer.revokePartitions(List.of(FIRST));
            reserve(consumer, FIRST, () -> { newStarted.countDown(); releaseNew.join(); }, 600L);
            ReflectionTestUtils.invokeMethod(consumer, "drainDeferred", kafka);
            assertTrue(newStarted.await(2, TimeUnit.SECONDS));
            assertEquals(1_200, aggregate(consumer, "totalPendingBytes"));
            assertEquals(2, aggregate(consumer, "totalPendingRecords"));
            pauseState(consumer, kafka);
            verify(kafka).pause(Set.of(FIRST));
            releaseNew.complete(null);
            drainLane(consumer, FIRST);
            ReflectionTestUtils.invokeMethod(consumer, "drainDeferred", kafka);
            pauseState(consumer, kafka);
            assertEquals(600, aggregate(consumer, "totalPendingBytes"));
            assertEquals(1, aggregate(consumer, "totalPendingRecords"));
            verify(kafka, never()).resume(any());
            releaseOld.complete(null);
            assertTrue(original.awaitTermination(2, TimeUnit.SECONDS));
            pauseState(consumer, kafka);
            verify(kafka).resume(Set.of(FIRST));
            consumer.stop();
            consumer.stop();
            assertEquals(0, aggregate(consumer, "totalPendingBytes"));
            assertEquals(0, aggregate(consumer, "totalPendingRecords"));
        } finally {
            releaseOld.complete(null);
            releaseNew.complete(null);
            consumer.stop();
        }
    }

    @Test
    void pendingReplayStopsReservingAtTheProcessLimitAndLeavesOtherRowsDurable() throws Exception {
        var store = mock(DetectionStateStore.class);
        var consumer = new KafkaEventConsumer(mock(DetectEngineService.class), store);
        ReflectionTestUtils.setField(consumer, "topic", FIRST.topic());
        ReflectionTestUtils.setField(consumer, "maxPendingRecords", 2L);
        var release = blockLane(consumer, FIRST);
        var rows = IntStream.range(0, 3).mapToObj(index ->
                new com.socp.detect.web.persistence.store.PendingDetectionEvent(
                        new com.socp.rule.model.SecurityEvent("replay-" + index, java.time.Instant.EPOCH,
                                "auth", "host", "raw", Map.of("tenant_id", "tenant-a"),
                                com.socp.rule.model.Severity.INFO), FIRST.partition(), (long) index)).toList();
        doAnswer(call -> {
            java.util.function.Consumer<List<com.socp.detect.web.persistence.store.PendingDetectionEvent>> callback = call.getArgument(3);
            callback.accept(rows);
            return null;
        }).when(store).replayPendingPages(anySet(), any(), eq(2), any());
        try {
            ReflectionTestUtils.invokeMethod(consumer, "replayPending", Set.of(FIRST.partition()));
            assertEquals(2, aggregate(consumer, "totalPendingRecords"));
            ReflectionTestUtils.invokeMethod(consumer, "replayPending", Set.of(FIRST.partition()));
            verify(store).replayPendingPages(anySet(), any(), eq(2), any());
            consumer.revokePartitions(List.of(FIRST));
            assertEquals(0, aggregate(consumer, "totalPendingRecords"));
            assertEquals(0, aggregate(consumer, "totalPendingBytes"));
            verify(store, never()).markCompleted(anyString(), anyString());
        } finally {
            release.complete(null);
            consumer.stop();
        }
    }

    @Test
    void reassignmentReappliesTheGlobalPauseBeforeThePollCanReturnMoreRecords() throws Exception {
        var consumer = new KafkaEventConsumer(mock(DetectEngineService.class), mock(DetectionStateStore.class));
        ReflectionTestUtils.setField(consumer, "bootstrap", "127.0.0.1:9092");
        ReflectionTestUtils.setField(consumer, "groupId", "reassignment-test");
        ReflectionTestUtils.setField(consumer, "topic", FIRST.topic());
        ReflectionTestUtils.setField(consumer, "maxPendingBytes", 500L);
        var oldStarted = new CountDownLatch(1);
        var releaseOld = new CompletableFuture<Void>();
        reserve(consumer, FIRST, () -> { oldStarted.countDown(); releaseOld.join(); }, 600L);
        ReflectionTestUtils.invokeMethod(consumer, "drainDeferred", (Object) null);
        assertTrue(oldStarted.await(2, TimeUnit.SECONDS));
        ThreadPoolExecutor original = ReflectionTestUtils.invokeMethod(consumer, "lane", FIRST.partition());
        var callback = new java.util.concurrent.atomic.AtomicReference<org.apache.kafka.clients.consumer.ConsumerRebalanceListener>();
        var assignment = new java.util.concurrent.atomic.AtomicReference<Set<TopicPartition>>(Set.of());
        var paused = new java.util.HashSet<TopicPartition>();
        var polls = new java.util.concurrent.atomic.AtomicInteger();
        var returnedRecords = new java.util.concurrent.atomic.AtomicInteger();
        try (var construction = mockConstruction(KafkaConsumer.class, (kafka, context) -> {
            doAnswer(call -> {
                callback.set(call.getArgument(1));
                return null;
            }).when(kafka).subscribe(anyCollection(), any(org.apache.kafka.clients.consumer.ConsumerRebalanceListener.class));
            when(kafka.assignment()).thenAnswer(call -> assignment.get());
            doAnswer(call -> { paused.addAll(call.getArgument(0)); return null; }).when(kafka).pause(anyCollection());
            doAnswer(call -> { paused.removeAll(call.getArgument(0)); return null; }).when(kafka).resume(anyCollection());
            when(kafka.poll(any(java.time.Duration.class))).thenAnswer(call -> {
                int round = polls.incrementAndGet();
                if (round <= 3) {
                    // Simulate the coordinator installing a new assignment and
                    // clearing its Kafka pause before invoking the real callback.
                    callback.get().onPartitionsRevoked(List.of(FIRST));
                    assignment.set(Set.of(FIRST));
                    paused.clear();
                    callback.get().onPartitionsAssigned(List.of(FIRST));
                    if (!paused.contains(FIRST)) {
                        returnedRecords.addAndGet(200);
                        return new ConsumerRecords<>(Map.of(FIRST, records(FIRST, round * 200, 200)));
                    }
                } else {
                    releaseOld.complete(null);
                    assertTrue(original.awaitTermination(2, TimeUnit.SECONDS));
                    ReflectionTestUtils.invokeMethod(consumer, "applyPauseState", kafka);
                    assertTrue(paused.isEmpty(), "the replacement resumes once the old reservation is released");
                    ((java.util.concurrent.atomic.AtomicBoolean) ReflectionTestUtils.getField(consumer, "running")).set(false);
                }
                return ConsumerRecords.empty();
            });
        })) {
            ReflectionTestUtils.invokeMethod(consumer, "runConsumerSession");
            assertEquals(4, polls.get());
            assertEquals(0, returnedRecords.get());
            assertEquals(0, aggregate(consumer, "totalPendingBytes"));
            assertEquals(0, aggregate(consumer, "totalPendingRecords"));
            verify(construction.constructed().getFirst(), times(3)).pause(Set.of(FIRST));
        } finally {
            releaseOld.complete(null);
            consumer.stop();
        }
    }

    private static KafkaConsumer<String, String> kafka(Set<TopicPartition> assignments) {
        @SuppressWarnings("unchecked") KafkaConsumer<String, String> kafka = mock(KafkaConsumer.class);
        when(kafka.assignment()).thenReturn(assignments);
        return kafka;
    }

    private static List<ConsumerRecord<String, String>> records(TopicPartition partition, int start, int count) {
        return IntStream.range(start, start + count).mapToObj(offset -> new ConsumerRecord<>(
                partition.topic(), partition.partition(), offset, "key-" + offset,
                "{\"eventId\":\"p" + partition.partition() + "-" + offset + "\",\"tenantId\":\"tenant-a\"}")).toList();
    }

    private static CompletableFuture<Void> blockLane(KafkaEventConsumer consumer, TopicPartition partition) throws Exception {
        var started = new CountDownLatch(1);
        var release = new CompletableFuture<Void>();
        ThreadPoolExecutor lane = ReflectionTestUtils.invokeMethod(consumer, "lane", partition.partition());
        lane.execute(() -> { started.countDown(); release.join(); });
        assertTrue(started.await(2, TimeUnit.SECONDS));
        return release;
    }

    private static void reserve(KafkaEventConsumer consumer, TopicPartition partition, Runnable task, long bytes) {
        ReflectionTestUtils.invokeMethod(consumer, "dispatchOrDefer", null, partition, task, bytes);
    }

    private static void dispatch(KafkaEventConsumer consumer, KafkaConsumer<String, String> kafka,
                                 Map<TopicPartition, List<ConsumerRecord<String, String>>> records) {
        ReflectionTestUtils.invokeMethod(consumer, "dispatchPolledRecords", kafka, new ConsumerRecords<>(records));
    }

    private static void pauseState(KafkaEventConsumer consumer, KafkaConsumer<String, String> kafka) {
        ReflectionTestUtils.invokeMethod(consumer, "applyPauseState", kafka);
    }

    private static void drainLane(KafkaEventConsumer consumer, TopicPartition partition) throws Exception {
        ThreadPoolExecutor lane = ReflectionTestUtils.invokeMethod(consumer, "lane", partition.partition());
        lane.submit(() -> { }).get(5, TimeUnit.SECONDS);
    }

    private static long aggregate(KafkaEventConsumer consumer, String field) {
        return ((AtomicLong) ReflectionTestUtils.getField(consumer, field)).get();
    }
}
