package com.socp.detect.web.engine;

import com.socp.detect.web.service.DetectEngineService;
import com.socp.detect.web.persistence.store.DetectionEventClaim;
import com.socp.detect.web.persistence.store.DetectionStateStore;
import com.socp.rule.model.SecurityEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class KafkaEventConsumerTest {

    @Mock
    private DetectEngineService engine;

    @Mock
    private DetectionStateStore stateStore;

    @Test
    void duplicateCompletedEventIdIsSubmittedOnlyOnce() {
        given(stateStore.claim(any(SecurityEvent.class), eq(null), eq(null), anyString()))
                .willReturn(DetectionEventClaim.NEW, DetectionEventClaim.COMPLETED);
        given(engine.ingestFromKafkaAndAwait(any(SecurityEvent.class)))
                .willReturn(CompletableFuture.completedFuture(null));
        KafkaEventConsumer consumer = new KafkaEventConsumer(engine, stateStore);
        String event = "{\"eventId\":\"consumer-test-100\",\"tenantId\":\"default\",\"source\":\"auth\",\"host\":\"web-1\",\"msg\":\"login failed\"}";

        consumer.processRecord("key-1", event);
        consumer.processRecord("key-2", event);

        verify(engine, times(1)).ingestFromKafkaAndAwait(any(SecurityEvent.class));
    }

    @Test
    void malformedEventIsSentToDlqWithoutReachingEngine() {
        KafkaEventConsumer consumer = new KafkaEventConsumer(engine);
        List<Map.Entry<String, String>> dlq = new ArrayList<>();
        consumer.setDlqSink((eventId, raw) -> dlq.add(
                new AbstractMap.SimpleEntry<>(eventId, raw)));
        String malformed = "{not-json";

        consumer.processRecord("bad-key", malformed);

        assertEquals(1, dlq.size());
        assertEquals(null, dlq.get(0).getKey());
        assertEquals(malformed, dlq.get(0).getValue());
        verify(engine, times(0)).ingestFromKafkaAndAwait(any(SecurityEvent.class));
    }

    @Test
    void transientEngineFailureRemainsPendingAndIsNotCommittedToDlq() {
        KafkaEventConsumer consumer = new KafkaEventConsumer(engine);
        List<Map.Entry<String, String>> dlq = new ArrayList<>();
        consumer.setDlqSink((eventId, raw) -> dlq.add(
                new AbstractMap.SimpleEntry<>(eventId, raw)));
        given(engine.ingestFromKafkaAndAwait(any(SecurityEvent.class)))
                .willReturn(CompletableFuture.failedFuture(new IllegalStateException("database unavailable")));
        String event = "{\"eventId\":\"consumer-test-101\",\"tenantId\":\"default\",\"source\":\"auth\","
                + "\"host\":\"web-1\",\"msg\":\"login failed\"}";

        consumer.processRecord("key-1", event);

        assertEquals(0, dlq.size());
        verify(engine).ingestFromKafkaAndAwait(any(SecurityEvent.class));
    }

    @Test
    void kafkaOwnershipMetadataIsPersistedWithTheEventClaim() {
        given(stateStore.claim(any(SecurityEvent.class), eq(2), eq(42L), anyString()))
                .willReturn(DetectionEventClaim.NEW);
        given(engine.ingestFromKafkaAndAwait(any(SecurityEvent.class)))
                .willReturn(CompletableFuture.completedFuture(null));
        KafkaEventConsumer consumer = new KafkaEventConsumer(engine, stateStore);

        consumer.processRecord(2, 42L, "default|src_ip|198.51.100.9",
                "{\"eventId\":\"partition-test-1\",\"tenantId\":\"default\",\"source\":\"auth\","
                        + "\"host\":\"web-1\",\"msg\":\"login failed\","
                        + "\"fields\":{\"src_ip\":\"198.51.100.9\"}}");

        verify(stateStore).claim(any(SecurityEvent.class), eq(2), eq(42L),
                eq("default|src_ip|198.51.100.9"));
        verify(stateStore, never()).markCompleted("partition-test-1");
        verify(engine).ingestFromKafkaAndAwait(any(SecurityEvent.class));
    }

    @Test
    void saturatedPartitionLaneBlocksAdmissionWithoutReorderingOffsets() throws Exception {
        List<Integer> executionOrder = new java.util.concurrent.CopyOnWriteArrayList<>();
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        ThreadPoolExecutor lane = new ThreadPoolExecutor(
                1, 1, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(1),
                KafkaEventConsumer.blockingLaneBackpressure());
        try {
            lane.execute(() -> {
                executionOrder.add(1);
                firstStarted.countDown();
                try {
                    releaseFirst.await();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            });
            assertTrue(firstStarted.await(1, TimeUnit.SECONDS));
            lane.execute(() -> executionOrder.add(2));

            CompletableFuture<Void> thirdAdmission = CompletableFuture.runAsync(
                    () -> lane.execute(() -> executionOrder.add(3)));
            Thread.sleep(100);
            assertFalse(thirdAdmission.isDone(), "saturated admission must apply backpressure");

            releaseFirst.countDown();
            thirdAdmission.get(1, TimeUnit.SECONDS);
            lane.shutdown();
            assertTrue(lane.awaitTermination(1, TimeUnit.SECONDS));
            assertEquals(List.of(1, 2, 3), executionOrder);
        } finally {
            releaseFirst.countDown();
            lane.shutdownNow();
        }
    }
}
