package com.socp.detect.web.engine;

import com.socp.detect.web.service.DetectEngineService;
import com.socp.detect.web.persistence.store.DetectionEventClaim;
import com.socp.detect.web.persistence.store.DetectionStateOwnership;
import com.socp.detect.web.persistence.store.DetectionStateStore;
import com.socp.rule.model.SecurityEvent;
import com.socp.rule.model.Severity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.time.Instant;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.nio.charset.StandardCharsets;
import java.util.Queue;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import com.socp.detect.web.persistence.store.PendingDetectionEvent;

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
    @Timeout(20)
    void aPolledRecordIsProcessedUnderItsOwnTraceAndPublishesACompletion() throws Exception {
        // A record that carries topic and position takes the four-argument
        // overload; stubbing the one-argument form would leave this null, and
        // the resulting failure sends the record down the durable DLQ path,
        // which retries against a broker that no unit test has.
        given(stateStore.claim(any(SecurityEvent.class), any(), any(), anyString()))
                .willReturn(DetectionEventClaim.NEW);
        given(engine.ingestFromKafkaAndAwait(any(SecurityEvent.class), anyString(), any(), any()))
                .willReturn(CompletableFuture.completedFuture(null));
        KafkaEventConsumer consumer = new KafkaEventConsumer(engine, stateStore);
        String event = "{\"eventId\":\"consumer-test-201\",\"tenantId\":\"default\",\"source\":\"auth\","
                + "\"host\":\"web-1\",\"msg\":\"login failed\"}";
        ConsumerRecord<String, String> record =
                new ConsumerRecord<>("socp-events", 3, 7L, "key-1", event);
        record.headers().add("traceparent",
                "00-0123456789abcdef0123456789abcdef-0123456789abcdef-01"
                        .getBytes(StandardCharsets.UTF_8));

        consumer.processWithRetry(record, 3L);

        verify(engine).ingestFromKafkaAndAwait(any(SecurityEvent.class), anyString(), any(), any());
        // The offset may only advance once the work is durable, so the batch
        // completion is what the committer waits for, not the return of process.
        assertEquals(1, completionsOf(consumer).size());
        assertNull(org.slf4j.MDC.get("traceId"),
                "the consume span must not leave its trace id behind");
    }

    @Test
    @Timeout(20)
    void aMalformedPolledRecordIsDeadLetteredAndStillPublishesACompletion() throws Exception {
        KafkaEventConsumer consumer = new KafkaEventConsumer(engine);
        List<Map.Entry<String, String>> dlq = new ArrayList<>();
        consumer.setDlqSink((eventId, raw) -> dlq.add(
                new AbstractMap.SimpleEntry<>(eventId, raw)));
        ConsumerRecord<String, String> record =
                new ConsumerRecord<>("socp-events", 3, 8L, "bad-key", "{not-json");

        consumer.processWithRetry(record, 3L);

        assertEquals(1, dlq.size());
        // A malformed record must not reach the engine through any overload.
        verifyNoInteractions(engine);
        assertEquals(1, completionsOf(consumer).size());
    }

    @Test
    @Timeout(20)
    void anUnreachableDeadLetterQueueGivesUpWithoutPublishingACompletion() throws Exception {
        KafkaEventConsumer consumer = new KafkaEventConsumer(engine);
        ReflectionTestUtils.setField(consumer, "dlqHandoffMaxAttempts", 3);
        ReflectionTestUtils.setField(consumer, "dlqHandoffRetryDelayMs", 10L);
        consumer.setDlqSink((eventId, raw) -> {
            throw new IllegalStateException("broker unreachable");
        });
        ConsumerRecord<String, String> record =
                new ConsumerRecord<>("socp-events", 3, 9L, "bad-key", "{not-json");

        consumer.processWithRetry(record, 3L);

        // Giving up must not publish a completion. That is what keeps the
        // offset uncommitted, so an unreachable broker costs a retry later
        // instead of a record silently dropped, or a lane wedged forever.
        assertEquals(0, completionsOf(consumer).size());
    }

    @Test
    @Timeout(20)
    void aDeadLetterIsPublishedToKafkaWithTheTraceOfTheRecordItReplaces() throws Exception {
        // Tracing on, because the propagation is what is under test: a no-op
        // propagator injects nothing and the assertion would pass for the wrong
        // reason.
        GlobalOpenTelemetry.resetForTest();
        GlobalOpenTelemetry.set(OpenTelemetrySdk.builder()
                .setTracerProvider(SdkTracerProvider.builder().build())
                .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
                .build());
        try {
        KafkaEventConsumer consumer = new KafkaEventConsumer(engine);
        KafkaProducer<String, String> dlq = mock(KafkaProducer.class);
        given(dlq.send(any(ProducerRecord.class)))
                .willReturn(CompletableFuture.completedFuture(mock(RecordMetadata.class)));
        ReflectionTestUtils.setField(consumer, "dlqProducer", dlq);
        String stored = "00-0123456789abcdef0123456789abcdef-0123456789abcdef-01";
        ConsumerRecord<String, String> record =
                new ConsumerRecord<>("socp-events", 3, 11L, "bad-key", "{not-json");
        record.headers().add("traceparent", stored.getBytes(StandardCharsets.UTF_8));

        consumer.processWithRetry(record, 3L);

        ArgumentCaptor<ProducerRecord<String, String>> captor =
                ArgumentCaptor.forClass(ProducerRecord.class);
        verify(dlq).send(captor.capture());
        assertEquals(stored, new String(
                captor.getValue().headers().lastHeader("traceparent").value(), StandardCharsets.UTF_8));
        assertEquals(1, completionsOf(consumer).size());
        } finally {
            GlobalOpenTelemetry.resetForTest();
        }
    }

    @Test
    @Timeout(20)
    void exhaustedRetriesHandOffThroughTheDeadLetterProducer() throws Exception {
        given(stateStore.claim(any(SecurityEvent.class), any(), any(), anyString()))
                .willReturn(DetectionEventClaim.NEW);
        given(engine.ingestFromKafkaAndAwait(any(SecurityEvent.class), anyString(), any(), any()))
                .willReturn(CompletableFuture.failedFuture(new IllegalStateException("sink unavailable")));
        KafkaEventConsumer consumer = new KafkaEventConsumer(engine, stateStore);
        ReflectionTestUtils.setField(consumer, "processingMaxAttempts", 1);
        KafkaProducer<String, String> dlq = mock(KafkaProducer.class);
        given(dlq.send(any(ProducerRecord.class)))
                .willReturn(CompletableFuture.completedFuture(mock(RecordMetadata.class)));
        ReflectionTestUtils.setField(consumer, "dlqProducer", dlq);
        ConsumerRecord<String, String> record = new ConsumerRecord<>("socp-events", 3, 12L, "key-1",
                "{\"eventId\":\"consumer-test-301\",\"tenantId\":\"default\",\"source\":\"auth\","
                        + "\"host\":\"web-1\",\"msg\":\"login failed\"}");

        consumer.processWithRetry(record, 3L);

        // Retries exhausted: the record is dead-lettered rather than retried forever.
        verify(dlq).send(any(ProducerRecord.class));
        assertEquals(1, completionsOf(consumer).size());
    }

    @Test
    void theOffsetAwareSeamAlsoDeadLetters() {
        KafkaEventConsumer consumer = new KafkaEventConsumer(engine);
        List<Map.Entry<String, String>> dlq = new ArrayList<>();
        consumer.setDlqSink((eventId, raw) -> dlq.add(new AbstractMap.SimpleEntry<>(eventId, raw)));

        consumer.processRecord(3, 13L, "bad-key", "{not-json");

        assertEquals(1, dlq.size());
        verifyNoInteractions(engine);
    }

    @Test
    void anExhaustedJournalReplayIsDeadLettered() {
        given(stateStore.claim(any(SecurityEvent.class), any(), any(), anyString()))
                .willThrow(new IllegalStateException("journal unavailable"));
        KafkaEventConsumer consumer = new KafkaEventConsumer(engine, stateStore);
        ReflectionTestUtils.setField(consumer, "processingMaxAttempts", 1);
        List<Map.Entry<String, String>> dlq = new ArrayList<>();
        consumer.setDlqSink((eventId, raw) -> dlq.add(new AbstractMap.SimpleEntry<>(eventId, raw)));
        SecurityEvent event = new SecurityEvent(Instant.now(), "auth", "web-1", "replay target",
                Map.of("tenantId", "default", "src_ip", "198.51.100.7"), Severity.INFO);

        consumer.processPendingWithRetry(new PendingDetectionEvent(event, 3, 21L));

        // A replayed row was already accepted once, so it must not be retried
        // forever: exhausting the attempts dead-letters it. It carries no Kafka
        // headers, so unlike the polled path there is no originating trace to
        // inherit - which is why the hand-off is called with null.
        assertEquals(1, dlq.size(), "the exhausted replay must be dead-lettered");
    }

    @Test
    @Timeout(20)
    void exhaustedRetriesTerminaliseTheNormalizedEventUnderItsOwnTenant() throws Exception {
        given(stateStore.claim(any(SecurityEvent.class), any(), any(), anyString()))
                .willReturn(DetectionEventClaim.NEW);
        given(engine.ingestFromKafkaAndAwait(any(SecurityEvent.class), anyString(), any(), any()))
                .willReturn(CompletableFuture.failedFuture(
                        new IllegalStateException("durable sink rejected this event")));
        KafkaEventConsumer consumer = new KafkaEventConsumer(engine, stateStore);
        ReflectionTestUtils.setField(consumer, "processingMaxAttempts", 1);
        List<Map.Entry<String, String>> dlq = new ArrayList<>();
        consumer.setDlqSink((eventId, raw) -> dlq.add(new AbstractMap.SimpleEntry<>(eventId, raw)));
        AtomicReference<String> tenantDuringHandOff = new AtomicReference<>();
        org.mockito.Mockito.doAnswer(invocation -> {
            tenantDuringHandOff.set(com.socp.platform.tenant.context.TenantContext.get());
            return null;
        }).when(stateStore).recordDeadLettered(anyString(), anyString(), any(), any(), anyString());
        ConsumerRecord<String, String> record = new ConsumerRecord<>("socp-events", 3, 12L,
                "default|src_ip|198.51.100.7",
                "{\"eventId\":\"poison-identity-1\",\"tenantId\":\"default\",\"source\":\"auth\","
                        + "\"host\":\"web-1\",\"msg\":\"login failed\","
                        + "\"fields\":{\"src_ip\":\"198.51.100.7\"}}");

        consumer.processWithRetry(record, 3L);

        // The Kafka routing key is shared by every event of one entity, so it can
        // never be the identity of a terminal record: the DLQ key and the journal
        // sourceEventId are the normalized event id on both hand-off paths.
        assertEquals(1, dlq.size());
        assertEquals("poison-identity-1", dlq.get(0).getKey());
        assertEquals(record.value(), dlq.get(0).getValue());
        verify(stateStore).recordDeadLettered(eq("poison-identity-1"), eq(record.value()),
                eq(3), eq(12L), anyString());
        // The tenant scope is closed by the time the lane hands off, so the write
        // only lands in the journal when the hand-off installs it explicitly.
        assertEquals("default", tenantDuringHandOff.get(),
                "the terminal journal row must be written in the event's tenant scope");
        assertEquals(1, completionsOf(consumer).size());
    }

    @Test
    @Timeout(30)
    void aGloballyUnavailableWorkerWithholdsTheOffsetInsteadOfDeadLettering() throws Exception {
        List<Throwable> globalFailures = List.of(
                new TenantAdmission.RejectedException("default", TenantAdmission.RejectionReason.RATE),
                new TenantAdmission.RejectedException("default",
                        TenantAdmission.RejectionReason.PENDING_BYTES),
                new IllegalStateException("detection state recovery is DEGRADED"),
                new IllegalStateException("detection runtime role is API"),
                new DetectionStateOwnership.StaleStateOwnerException(
                        "Kafka partition is no longer assigned: 3"));
        given(stateStore.claim(any(SecurityEvent.class), any(), any(), anyString()))
                .willReturn(DetectionEventClaim.NEW);
        for (Throwable global : globalFailures) {
            KafkaEventConsumer consumer = new KafkaEventConsumer(engine, stateStore);
            ReflectionTestUtils.setField(consumer, "processingMaxAttempts", 2);
            List<Map.Entry<String, String>> dlq = new ArrayList<>();
            consumer.setDlqSink((eventId, raw) -> dlq.add(new AbstractMap.SimpleEntry<>(eventId, raw)));
            given(engine.ingestFromKafkaAndAwait(any(SecurityEvent.class), anyString(), any(), any()))
                    .willReturn(CompletableFuture.failedFuture(global));
            ConsumerRecord<String, String> record = new ConsumerRecord<>("socp-events", 3, 40L,
                    "default|src_ip|198.51.100.7",
                    "{\"eventId\":\"unavailable-1\",\"tenantId\":\"default\",\"source\":\"auth\","
                            + "\"host\":\"web-1\",\"msg\":\"login failed\","
                            + "\"fields\":{\"src_ip\":\"198.51.100.7\"}}");

            consumer.processWithRetry(record, 3L);

            // A worker-wide outage must not be silently converted into a committed
            // offset over an unevaluated event: no dead-letter, no terminal journal
            // row, and no completion, so the offset stays pending and redelivery or
            // the PENDING journal row drives the record again once the worker heals.
            assertEquals(List.of(), dlq, "unavailable failure " + global + " must not dead-letter");
            assertEquals(0, completionsOf(consumer).size(),
                    "unavailable failure " + global + " must not commit an offset");
            verify(stateStore, never()).recordDeadLettered(anyString(), anyString(), any(), any(),
                    anyString());
        }
        // Two attempts per condition: the budget is spent, then the record is
        // withheld rather than terminalised.
        verify(engine, times(globalFailures.size() * 2)).ingestFromKafkaAndAwait(
                any(SecurityEvent.class), anyString(), any(), any());
        // A worker-wide outage is not instance state corruption: rebuilding the
        // whole engine once per withheld record would amplify one outage into a
        // rebuild storm on every lane.
        verify(engine, never()).rebuildForPartitions(any());
    }

    @Test
    @Timeout(30)
    void aWithheldJournalReplayStaysPendingAndIsNotTerminalised() {
        given(engine.ingestFromKafkaAndAwait(any(SecurityEvent.class), any(), any()))
                .willReturn(CompletableFuture.failedFuture(
                        new IllegalStateException("detection state recovery is RECOVERING")));
        KafkaEventConsumer consumer = new KafkaEventConsumer(engine, stateStore);
        ReflectionTestUtils.setField(consumer, "processingMaxAttempts", 2);
        List<Map.Entry<String, String>> dlq = new ArrayList<>();
        consumer.setDlqSink((eventId, raw) -> dlq.add(new AbstractMap.SimpleEntry<>(eventId, raw)));
        SecurityEvent event = new SecurityEvent(Instant.now(), "auth", "web-1", "replay target",
                Map.of("tenantId", "default", "src_ip", "198.51.100.7"), Severity.INFO);

        consumer.processPendingWithRetry(new PendingDetectionEvent(event, 3, 41L));

        assertEquals(List.of(), dlq, "a replay blocked by global unavailability must stay PENDING");
        verify(stateStore, never()).recordDeadLettered(anyString(), anyString(), any(), any(), anyString());
    }

    private static java.util.Queue<?> completionsOf(KafkaEventConsumer consumer) throws Exception {
        Field field = KafkaEventConsumer.class.getDeclaredField("completions");
        field.setAccessible(true);
        return (java.util.Queue<?>) field.get(consumer);
    }

    @Test
    void kafkaOwnershipMetadataIsPersistedWithTheEventClaim() {
        given(stateStore.claim(any(SecurityEvent.class), eq(2), eq(42L), anyString()))
                .willReturn(DetectionEventClaim.NEW);
        given(engine.ingestFromKafkaAndAwait(any(SecurityEvent.class), eq(2), eq(42L)))
                .willReturn(CompletableFuture.completedFuture(null));
        KafkaEventConsumer consumer = new KafkaEventConsumer(engine, stateStore);

        consumer.processRecord(2, 42L, "default|src_ip|198.51.100.9",
                "{\"eventId\":\"partition-test-1\",\"tenantId\":\"default\",\"source\":\"auth\","
                        + "\"host\":\"web-1\",\"msg\":\"login failed\","
                        + "\"fields\":{\"src_ip\":\"198.51.100.9\"}}");

        verify(stateStore).claim(any(SecurityEvent.class), eq(2), eq(42L),
                eq("default|src_ip|198.51.100.9"));
        verify(stateStore, never()).markCompleted("partition-test-1");
        verify(engine).ingestFromKafkaAndAwait(any(SecurityEvent.class), eq(2), eq(42L));
    }

    @Test
    void saturatedPartitionLaneRejectsImmediatelyWithoutBlockingTheAdmissionThread() throws Exception {
        List<Integer> executionOrder = new java.util.concurrent.CopyOnWriteArrayList<>();
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        ThreadPoolExecutor lane = new ThreadPoolExecutor(
                1, 1, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(1),
                KafkaEventConsumer.nonBlockingLaneBackpressure());
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

            CompletableFuture<Void> thirdAdmission = CompletableFuture.runAsync(() ->
                    assertThrows(java.util.concurrent.RejectedExecutionException.class,
                            () -> lane.execute(() -> executionOrder.add(3))));
            thirdAdmission.get(1, TimeUnit.SECONDS);

            releaseFirst.countDown();
            lane.shutdown();
            assertTrue(lane.awaitTermination(1, TimeUnit.SECONDS));
            assertEquals(List.of(1, 2), executionOrder);
        } finally {
            releaseFirst.countDown();
            lane.shutdownNow();
        }
    }

    @Test
    void pausesOnlyTheSaturatedPartitionAndResumesAfterDeferredWorkDrains() throws Exception {
        KafkaEventConsumer consumer = new KafkaEventConsumer(engine);
        KafkaConsumer<String, String> kafka = mock(KafkaConsumer.class);
        TopicPartition partition = new TopicPartition("events", 3);
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondFinished = new CountDownLatch(1);
        ThreadPoolExecutor lane = new ThreadPoolExecutor(
                1, 1, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(1));
        lane.execute(() -> {
            firstStarted.countDown();
            try {
                releaseFirst.await(2, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });
        lane.execute(secondFinished::countDown);
        assertTrue(firstStarted.await(1, TimeUnit.SECONDS));

        Field lanes = KafkaEventConsumer.class.getDeclaredField("partitionLanes");
        lanes.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<Integer, ThreadPoolExecutor> laneMap = (Map<Integer, ThreadPoolExecutor>) lanes.get(consumer);
        laneMap.put(partition.partition(), lane);

        Method dispatch = KafkaEventConsumer.class.getDeclaredMethod(
                "dispatchOrDefer", KafkaConsumer.class, TopicPartition.class, Runnable.class);
        dispatch.setAccessible(true);
        dispatch.invoke(consumer, kafka, partition, (Runnable) secondFinished::countDown);
        verify(kafka).pause(eq(java.util.Set.of(partition)));

        releaseFirst.countDown();
        assertTrue(secondFinished.await(2, TimeUnit.SECONDS));
        Method drain = KafkaEventConsumer.class.getDeclaredMethod("drainDeferred", KafkaConsumer.class);
        drain.setAccessible(true);
        drain.invoke(consumer, kafka);

        verify(kafka).resume(eq(java.util.Set.of(partition)));
        consumer.stop();
    }

    @Test
    void byteBudgetPausesAQuietPartitionBeforeAdmittingAnOversizedWorkItem() throws Exception {
        KafkaEventConsumer consumer = new KafkaEventConsumer(engine);
        KafkaConsumer<String, String> kafka = mock(KafkaConsumer.class);
        TopicPartition partition = new TopicPartition("events", 7);
        CountDownLatch completed = new CountDownLatch(1);

        Field budget = KafkaEventConsumer.class.getDeclaredField("partitionMaxPendingBytes");
        budget.setAccessible(true);
        budget.setLong(consumer, 4L);
        Method dispatch = KafkaEventConsumer.class.getDeclaredMethod(
                "dispatchOrDefer", KafkaConsumer.class, TopicPartition.class, Runnable.class, long.class);
        dispatch.setAccessible(true);
        dispatch.invoke(consumer, kafka, partition, (Runnable) completed::countDown, 32L);

        verify(kafka).pause(eq(java.util.Set.of(partition)));
        assertTrue(!completed.await(100, TimeUnit.MILLISECONDS));

        Method drain = KafkaEventConsumer.class.getDeclaredMethod("drainDeferred", KafkaConsumer.class);
        drain.setAccessible(true);
        drain.invoke(consumer, kafka);

        assertTrue(completed.await(1, TimeUnit.SECONDS));
        verify(kafka).resume(eq(java.util.Set.of(partition)));
        consumer.stop();
    }

    @Test
    void estimatesAndReleasesQueuedWorkWithoutLeakingTheByteBudget() throws Exception {
        Method recordBytes = KafkaEventConsumer.class.getDeclaredMethod(
                "estimateRecordBytes", org.apache.kafka.clients.consumer.ConsumerRecord.class);
        recordBytes.setAccessible(true);
        assertEquals(1L, recordBytes.invoke(null, new Object[]{null}));
        var record = new org.apache.kafka.clients.consumer.ConsumerRecord<>(
                "events", 1, 2L, "key", "payload");
        assertEquals(256L + 3L + 7L, recordBytes.invoke(null, record));

        Method eventBytes = KafkaEventConsumer.class.getDeclaredMethod(
                "estimateEventBytes", SecurityEvent.class);
        eventBytes.setAccessible(true);
        assertEquals(1L, eventBytes.invoke(null, new Object[]{null}));
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put(null, null);
        fields.put("key", "value");
        SecurityEvent event = new SecurityEvent("event-bytes", Instant.EPOCH, "auth", "host",
                "raw", fields, Severity.INFO);
        long expected = 256L + 3L + 3L + 5L;
        assertEquals(expected, eventBytes.invoke(null, event));

        Class<?> pendingType = KafkaEventConsumer.class.getDeclaredClasses()[0];
        for (Class<?> nested : KafkaEventConsumer.class.getDeclaredClasses()) {
            if (nested.getSimpleName().equals("PendingWork")) pendingType = nested;
        }
        AtomicLong counter = new AtomicLong(9L);
        var pendingConstructor = pendingType.getDeclaredConstructor(Runnable.class, long.class, AtomicLong.class);
        pendingConstructor.setAccessible(true);
        Runnable delegate = () -> { };
        Runnable pending = (Runnable) pendingConstructor.newInstance(delegate, 4L, counter);
        pending.run();
        assertEquals(5L, counter.get());

        Runnable dropped = (Runnable) pendingConstructor.newInstance(delegate, 3L, counter);
        Method releaseDropped = KafkaEventConsumer.class.getDeclaredMethod("releaseDropped", List.class);
        releaseDropped.setAccessible(true);
        releaseDropped.invoke(null, List.of(dropped));
        assertEquals(2L, counter.get());

        Runnable deferred = (Runnable) pendingConstructor.newInstance(delegate, 2L, counter);
        Method releaseDeferred = KafkaEventConsumer.class.getDeclaredMethod(
                "releaseDeferred", ArrayDeque.class);
        releaseDeferred.setAccessible(true);
        ArrayDeque<Runnable> queue = new ArrayDeque<>();
        queue.add(deferred);
        releaseDeferred.invoke(null, queue);
        assertEquals(0L, counter.get());
        releaseDeferred.invoke(null, new Object[]{null});
        releaseDropped.invoke(null, new Object[]{null});
    }
}
