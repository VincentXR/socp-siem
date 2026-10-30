package com.socp.detect.web.engine;

import com.socp.detect.web.persistence.store.DetectionEventClaim;
import com.socp.detect.web.persistence.store.DetectionStateStore;
import com.socp.detect.web.service.DetectEngineService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@Timeout(15)
class KafkaEventBatchTest {
    private static final TopicPartition PARTITION = new TopicPartition("socp-events", 0);
    private final DetectionStateStore store = mock(DetectionStateStore.class);
    private final DetectEngineService engine = mock(DetectEngineService.class);

    private KafkaEventConsumer consumer() {
        when(store.supportsBatchClaims()).thenReturn(true);
        when(engine.completesJournal(store)).thenReturn(true);
        when(engine.batchKey(any())).thenAnswer(call ->
                new DetectEngineService.BatchKey(((com.socp.rule.model.SecurityEvent) call.getArgument(0)).requireTenantId(), 0));
        return new KafkaEventConsumer(engine, store);
    }

    @Test
    void successiveQueuedPollsShareOneOrderedAdmissionAndRetainTheirReservations() throws Exception {
        KafkaEventConsumer consumer = consumer();
        CountDownLatch releaseLane = blockLane(consumer);
        var submitted = new CountDownLatch(1);
        List<CompletableFuture<Void>> futures = List.of(new CompletableFuture<>(), new CompletableFuture<>(),
                new CompletableFuture<>(), new CompletableFuture<>());
        when(store.claimBatch(any())).thenAnswer(call -> {
            List<DetectionStateStore.Input> inputs = call.getArgument(0);
            assertEquals(List.of(0L, 1L, 2L, 3L), inputs.stream().map(DetectionStateStore.Input::offset).toList());
            return java.util.Collections.nCopies(inputs.size(), DetectionEventClaim.NEW);
        });
        when(engine.ingestBatchFromKafkaAndAwait(anyList(), any())).thenAnswer(call -> {
            List<DetectEngineService.KafkaInput> inputs = call.getArgument(0);
            assertEquals(List.of(0L, 1L, 2L, 3L), inputs.stream().map(DetectEngineService.KafkaInput::offset).toList());
            submitted.countDown();
            return futures;
        });
        try {
            dispatch(consumer, List.of(record(0, "a"), record(1, "b")));
            dispatch(consumer, List.of(record(2, "c"), record(3, "d")));
            AtomicLong counter = pendingCounter(consumer);
            long reserved = counter.get();
            releaseLane.countDown();
            assertTrue(submitted.await(2, TimeUnit.SECONDS));
            assertEquals(reserved, counter.get(), "absorbed tasks remain reserved until the group settles");
            assertEquals(reserved, aggregate(consumer, "totalPendingBytes"));
            assertEquals(4, aggregate(consumer, "totalPendingRecords"));
            futures.subList(0, 3).forEach(future -> future.complete(null));
            assertTrue(completions(consumer).isEmpty());
            futures.getLast().complete(null);
            drainLane(consumer);
            assertEquals(4, completions(consumer).size());
            assertEquals(0, counter.get());
            assertEquals(0, aggregate(consumer, "totalPendingBytes"));
            assertEquals(0, aggregate(consumer, "totalPendingRecords"));
            verify(store, times(1)).claimBatch(any());
            verify(engine, times(1)).ingestBatchFromKafkaAndAwait(anyList(), any());
            verify(engine, never()).ingestFromKafkaAndAwait(any(), anyString(), any(), any());
        } finally { releaseLane.countDown(); consumer.stop(); }
    }

    @Test
    void queuedTaskAndMalformedRecordRemainSerialBarriers() throws Exception {
        KafkaEventConsumer consumer = consumer();
        CountDownLatch releaseLane = blockLane(consumer);
        List<String> order = new ArrayList<>();
        consumer.setDlqSink((id, raw) -> order.add("dlq"));
        when(store.claimBatch(any())).thenAnswer(call ->
                java.util.Collections.nCopies(((List<?>) call.getArgument(0)).size(), DetectionEventClaim.NEW));
        when(engine.ingestBatchFromKafkaAndAwait(anyList(), any())).thenAnswer(call -> {
            List<DetectEngineService.KafkaInput> inputs = call.getArgument(0);
            order.add(inputs.stream().map(input -> input.event().id()).toList().toString());
            return java.util.Collections.nCopies(inputs.size(), CompletableFuture.completedFuture(null));
        });
        try {
            dispatch(consumer, List.of(record(0, "a"), record(1, "b")));
            ReflectionTestUtils.invokeMethod(consumer, "dispatchOrDefer", null, PARTITION,
                    (Runnable) () -> order.add("task"));
            dispatch(consumer, List.of(record(2, "c"), record(3, "d")));
            dispatch(consumer, List.of(new ConsumerRecord<>(PARTITION.topic(), PARTITION.partition(),
                    4L, "key", "{bad-json"), record(5, "e"), record(6, "f")));
            AtomicLong counter = pendingCounter(consumer);
            releaseLane.countDown();
            drainLane(consumer);
            assertEquals(List.of("[a, b]", "task", "[c, d]", "dlq", "[e, f]"), order);
            assertEquals(7, completions(consumer).size());
            assertEquals(0, counter.get());
        } finally { releaseLane.countDown(); consumer.stop(); }
    }

    @ParameterizedTest
    @CsvSource({"32,32", "1000,64"})
    void coalescingKeepsConfiguredAndHardCountBounds(int configuredLimit, int effectiveLimit) throws Exception {
        KafkaEventConsumer consumer = consumer();
        ReflectionTestUtils.setField(consumer, "processingBatchSize", configuredLimit);
        CountDownLatch releaseLane = blockLane(consumer);
        List<List<Long>> claims = new ArrayList<>();
        when(store.claimBatch(any())).thenAnswer(call -> {
            List<DetectionStateStore.Input> inputs = call.getArgument(0);
            claims.add(inputs.stream().map(DetectionStateStore.Input::offset).toList());
            return java.util.Collections.nCopies(inputs.size(), DetectionEventClaim.NEW);
        });
        when(engine.ingestBatchFromKafkaAndAwait(anyList(), any())).thenAnswer(call ->
                java.util.Collections.nCopies(((List<?>) call.getArgument(0)).size(), CompletableFuture.completedFuture(null)));
        try {
            for (int i = 0; i < effectiveLimit + 2; i++) dispatch(consumer, List.of(record(i, "e-" + i)));
            AtomicLong counter = pendingCounter(consumer);
            releaseLane.countDown();
            drainLane(consumer);
            assertEquals(List.of(effectiveLimit, 2), claims.stream().map(List::size).toList());
            assertEquals(java.util.stream.LongStream.range(0, effectiveLimit + 2).boxed().toList(),
                    claims.stream().flatMap(List::stream).toList());
            assertEquals(effectiveLimit + 2, completions(consumer).size());
            assertEquals(0, counter.get());
        } finally { releaseLane.countDown(); consumer.stop(); }
    }

    @ParameterizedTest
    @CsvSource({"16777216,600000,1100000", "1000,400,2000"})
    void coalescingKeepsByteBoundsAndAllowsOneOversizedRecord(long partitionLimit,
                                                            int payloadSize, int oversizedPayload) throws Exception {
        KafkaEventConsumer consumer = consumer();
        ReflectionTestUtils.setField(consumer, "partitionMaxPendingBytes", partitionLimit);
        CountDownLatch releaseLane = blockLane(consumer);
        List<Integer> claimSizes = new ArrayList<>();
        when(store.claimBatch(any())).thenAnswer(call -> {
            List<?> inputs = call.getArgument(0);
            claimSizes.add(inputs.size());
            return java.util.Collections.nCopies(inputs.size(), DetectionEventClaim.NEW);
        });
        when(engine.ingestFromKafkaAndAwait(any(), anyString(), any(), any()))
                .thenReturn(CompletableFuture.completedFuture(null));
        try {
            dispatch(consumer, List.of(recordWithPayload(0, payloadSize)));
            dispatch(consumer, List.of(recordWithPayload(1, payloadSize)));
            dispatch(consumer, List.of(recordWithPayload(2, oversizedPayload)));
            // The poller can move its reserved overshoot into the lane while it remains paused.
            ReflectionTestUtils.invokeMethod(consumer, "drainDeferred", (Object) null);
            AtomicLong counter = pendingCounter(consumer);
            releaseLane.countDown();
            drainLane(consumer);
            assertEquals(List.of(1, 1, 1), claimSizes);
            assertEquals(3, completions(consumer).size());
            assertEquals(0, counter.get());
            verify(engine, never()).ingestBatchFromKafkaAndAwait(anyList(), any());
        } finally { releaseLane.countDown(); consumer.stop(); }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void revokeOrShutdownReleasesRunningAbsorbedAndQueuedReservations(boolean shutdown) throws Exception {
        KafkaEventConsumer consumer = consumer();
        ReflectionTestUtils.setField(consumer, "processingBatchSize", 4);
        CountDownLatch releaseLane = blockLane(consumer);
        var submitted = new CountDownLatch(1);
        var admissionGuard = new java.util.concurrent.atomic.AtomicReference<java.util.function.BooleanSupplier>();
        List<CompletableFuture<Void>> futures = List.of(new CompletableFuture<>(), new CompletableFuture<>(),
                new CompletableFuture<>(), new CompletableFuture<>());
        when(store.claimBatch(any())).thenReturn(java.util.Collections.nCopies(4, DetectionEventClaim.NEW));
        when(engine.ingestBatchFromKafkaAndAwait(anyList(), any())).thenAnswer(call -> {
            assertEquals(4, ((List<?>) call.getArgument(0)).size());
            admissionGuard.set(call.getArgument(1));
            submitted.countDown();
            return futures;
        });
        try {
            dispatch(consumer, List.of(record(0, "a"), record(1, "b")));
            dispatch(consumer, List.of(record(2, "c"), record(3, "d")));
            dispatch(consumer, List.of(record(4, "e"), record(5, "f")));
            AtomicLong counter = pendingCounter(consumer);
            ThreadPoolExecutor original = ReflectionTestUtils.invokeMethod(consumer, "lane", 0);
            releaseLane.countDown();
            assertTrue(submitted.await(2, TimeUnit.SECONDS));
            assertTrue(admissionGuard.get().getAsBoolean());
            if (shutdown) consumer.stop();
            else consumer.revokePartitions(List.of(PARTITION));
            assertFalse(admissionGuard.get().getAsBoolean());
            if (!shutdown) {
                PartitionRetryState retry = (PartitionRetryState) ReflectionTestUtils.getField(consumer, "retryState");
                retry.acquire(PARTITION);
                assertFalse(admissionGuard.get().getAsBoolean(), "a new assignment cannot revive the old admission guard");
            }
            assertTrue(original.awaitTermination(2, TimeUnit.SECONDS));
            futures.forEach(future -> future.complete(null));
            assertTrue(completions(consumer).isEmpty());
            assertEquals(0, counter.get());
            consumer.stop();
            assertEquals(0, counter.get(), "cleanup must not release any reservation twice");
            assertEquals(0, aggregate(consumer, "totalPendingBytes"));
            assertEquals(0, aggregate(consumer, "totalPendingRecords"));
            verify(store, times(1)).claimBatch(any());
        } finally { releaseLane.countDown(); consumer.stop(); }
    }

    @Test
    void pollerReturnsWhileLanePreclaimsAndWaitsForTheWholeBoundedGroup() throws Exception {
        KafkaEventConsumer consumer = consumer();
        var claimEntered = new CountDownLatch(1);
        var releaseClaim = new CountDownLatch(1);
        var submitted = new CountDownLatch(1);
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (int i = 0; i < 32; i++) futures.add(new CompletableFuture<>());
        when(store.claimBatch(any())).thenAnswer(call -> {
            claimEntered.countDown();
            assertTrue(releaseClaim.await(5, TimeUnit.SECONDS));
            List<?> inputs = call.getArgument(0);
            assertEquals(32, inputs.size());
            return java.util.Collections.nCopies(inputs.size(), DetectionEventClaim.NEW);
        });
        when(engine.ingestBatchFromKafkaAndAwait(anyList(), any())).thenAnswer(call -> {
            assertEquals(32, ((List<?>) call.getArgument(0)).size());
            submitted.countDown();
            return futures;
        });
        try {
            dispatch(consumer, java.util.stream.IntStream.range(0, 32).mapToObj(i -> record(i, "e-" + i)).toList());
            assertTrue(claimEntered.await(2, TimeUnit.SECONDS));
            assertTrue(completions(consumer).isEmpty());
            releaseClaim.countDown();
            assertTrue(submitted.await(2, TimeUnit.SECONDS));
            futures.subList(0, 31).forEach(f -> f.complete(null));
            assertTrue(completions(consumer).isEmpty(), "the original group is still live");
            futures.getLast().complete(null);
            drainLane(consumer);
            assertEquals(32, completions(consumer).size());
            verify(engine, never()).ingestFromKafkaAndAwait(any(), anyString(), any(), any());
        } finally { releaseClaim.countDown(); consumer.stop(); }
    }

    @Test
    void failedSuffixWaitsForOriginalFuturesAndRechecksUncertainCommits() throws Exception {
        KafkaEventConsumer consumer = consumer();
        CountDownLatch releaseLane = blockLane(consumer);
        var submitted = new CountDownLatch(1);
        var last = new CompletableFuture<Void>();
        when(store.claimBatch(any())).thenReturn(java.util.Collections.nCopies(3, DetectionEventClaim.NEW));
        when(engine.ingestBatchFromKafkaAndAwait(anyList(), any())).thenAnswer(call -> {
            submitted.countDown();
            return List.of(CompletableFuture.completedFuture(null),
                    CompletableFuture.failedFuture(new IllegalStateException("commit response lost")), last);
        });
        List<String> rechecked = new ArrayList<>();
        when(store.claim(any(), anyString(), any(), any(), anyString())).thenAnswer(call -> {
            String id = ((com.socp.rule.model.SecurityEvent) call.getArgument(0)).id();
            rechecked.add(id);
            return id.equals("e-1") ? DetectionEventClaim.COMPLETED : DetectionEventClaim.PENDING;
        });
        when(engine.ingestFromKafkaAndAwait(any(), anyString(), any(), any()))
                .thenReturn(CompletableFuture.completedFuture(null));
        try {
            dispatch(consumer, List.of(record(0, "e-0"), record(1, "e-1")));
            dispatch(consumer, List.of(record(2, "e-2")));
            AtomicLong counter = pendingCounter(consumer);
            releaseLane.countDown();
            assertTrue(submitted.await(2, TimeUnit.SECONDS));
            verify(store, never()).claim(any(), anyString(), any(), any(), anyString());
            last.completeExceptionally(new IllegalStateException("predecessor failed"));
            drainLane(consumer);
            assertEquals(List.of("e-1", "e-2"), rechecked);
            assertEquals(3, completions(consumer).size());
            assertEquals(0, counter.get());
            verify(engine, times(1)).ingestFromKafkaAndAwait(any(), anyString(), any(), any());
        } finally { releaseLane.countDown(); consumer.stop(); }
    }

    @Test
    void duplicateDeliveryCreatesASerialReceiptBarrier() throws Exception {
        KafkaEventConsumer consumer = consumer();
        var seen = new HashSet<String>();
        when(store.claimBatch(any())).thenAnswer(call -> {
            List<DetectionStateStore.Input> inputs = call.getArgument(0);
            return inputs.stream().map(input -> seen.add(input.event().id())
                    ? DetectionEventClaim.NEW : DetectionEventClaim.COMPLETED).toList();
        });
        when(engine.ingestBatchFromKafkaAndAwait(anyList(), any())).thenAnswer(call ->
                java.util.Collections.nCopies(((List<?>) call.getArgument(0)).size(), CompletableFuture.completedFuture(null)));
        when(engine.ingestFromKafkaAndAwait(any(), anyString(), any(), any()))
                .thenReturn(CompletableFuture.completedFuture(null));
        try {
            dispatch(consumer, List.of(record(0, "a"), record(1, "b"), record(2, "a"), record(3, "c")));
            drainLane(consumer);
            assertEquals(4, completions(consumer).size());
            verify(engine, times(1)).ingestBatchFromKafkaAndAwait(anyList(), any());
            verify(engine, times(1)).ingestFromKafkaAndAwait(argThat(e -> e.id().equals("c")), anyString(), any(), any());
        } finally { consumer.stop(); }
    }

    @Test
    void revokedBatchCannotAcknowledgeLateCompletions() throws Exception {
        KafkaEventConsumer consumer = consumer();
        var submitted = new CountDownLatch(1);
        var first = new CompletableFuture<Void>();
        var second = new CompletableFuture<Void>();
        when(store.claimBatch(any())).thenReturn(List.of(DetectionEventClaim.NEW, DetectionEventClaim.NEW));
        when(engine.ingestBatchFromKafkaAndAwait(anyList(), any())).thenAnswer(call -> {
            submitted.countDown(); return List.of(first, second);
        });
        try {
            dispatch(consumer, List.of(record(0, "a"), record(1, "b")));
            assertTrue(submitted.await(2, TimeUnit.SECONDS));
            ThreadPoolExecutor original = ReflectionTestUtils.invokeMethod(consumer, "lane", 0);
            consumer.revokePartitions(List.of(PARTITION));
            first.complete(null); second.complete(null);
            assertTrue(original.awaitTermination(2, TimeUnit.SECONDS));
            assertTrue(completions(consumer).isEmpty());
        } finally { consumer.stop(); }
    }

    @Test
    void ownershipLossStopsTheFailedSuffixAndRetiresQueuedWork() throws Exception {
        KafkaEventConsumer consumer = consumer();
        CountDownLatch releaseLane = blockLane(consumer);
        when(store.claimBatch(any())).thenReturn(List.of(DetectionEventClaim.NEW, DetectionEventClaim.NEW));
        when(engine.ingestBatchFromKafkaAndAwait(anyList(), any())).thenReturn(List.of(
                CompletableFuture.failedFuture(new IllegalStateException("batch failed")),
                CompletableFuture.failedFuture(new IllegalStateException("predecessor failed"))));
        when(store.claim(any(), anyString(), any(), any(), anyString())).thenThrow(
                new com.socp.detect.web.persistence.store.DetectionStateOwnership.StaleStateOwnerException("fenced"));
        try {
            dispatch(consumer, List.of(record(0, "a")));
            dispatch(consumer, List.of(record(1, "b")));
            AtomicLong counter = pendingCounter(consumer);
            releaseLane.countDown();
            drainLane(consumer);
            verify(store, times(1)).claim(any(), anyString(), any(), any(), anyString());
            assertTrue(completions(consumer).isEmpty());
            PartitionRetryState retry = (PartitionRetryState) ReflectionTestUtils.getField(consumer, "retryState");
            assertFalse(retry.active(retry.acquire(PARTITION)), "polling must not resurrect the retired lease");
            dispatch(consumer, List.of(record(2, "c")));
            drainLane(consumer);
            verify(store, times(1)).claimBatch(any());
            assertEquals(0, counter.get());
        } finally { releaseLane.countDown(); consumer.stop(); }
    }

    private static CountDownLatch blockLane(KafkaEventConsumer consumer) throws Exception {
        ThreadPoolExecutor lane = ReflectionTestUtils.invokeMethod(consumer, "lane", 0);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        lane.execute(() -> {
            entered.countDown();
            try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        });
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        return release;
    }

    @SuppressWarnings("unchecked")
    private static AtomicLong pendingCounter(KafkaEventConsumer consumer) {
        return ((Map<TopicPartition, AtomicLong>) ReflectionTestUtils.getField(consumer, "pendingBytes")).get(PARTITION);
    }

    private static ConsumerRecord<String, String> recordWithPayload(long offset, int payloadSize) {
        return new ConsumerRecord<>(PARTITION.topic(), PARTITION.partition(), offset, "key",
                "{\"eventId\":\"e-" + offset + "\",\"tenantId\":\"tenant-a\",\"msg\":\"" + "x".repeat(payloadSize) + "\"}");
    }

    private static long aggregate(KafkaEventConsumer consumer, String field) {
        return ((AtomicLong) ReflectionTestUtils.getField(consumer, field)).get();
    }

    private static ConsumerRecord<String, String> record(long offset, String id) {
        return new ConsumerRecord<>(PARTITION.topic(), PARTITION.partition(), offset, "key",
                "{\"eventId\":\"" + id + "\",\"tenantId\":\"tenant-a\"}");
    }

    private static void dispatch(KafkaEventConsumer consumer, List<ConsumerRecord<String, String>> records) {
        ReflectionTestUtils.invokeMethod(consumer, "dispatchPolledRecords", null,
                new ConsumerRecords<>(Map.of(PARTITION, records)));
    }

    private static void drainLane(KafkaEventConsumer consumer) throws Exception {
        ThreadPoolExecutor lane = ReflectionTestUtils.invokeMethod(consumer, "lane", 0);
        lane.submit(() -> { }).get(5, TimeUnit.SECONDS);
    }

    private static Queue<?> completions(KafkaEventConsumer consumer) {
        return (Queue<?>) ReflectionTestUtils.getField(consumer, "completions");
    }
}
