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
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.CannotCreateTransactionException;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.time.Instant;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CompletionException;
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
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
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

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    @Timeout(20)
    void lateFailureCannotPauseAReassignedPartition(boolean pendingReplay) throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch releaseFailure = new CountDownLatch(1);
        AtomicInteger claims = new AtomicInteger();
        org.mockito.stubbing.Answer<DetectionEventClaim> claim = invocation -> {
            if (claims.incrementAndGet() > 1) return DetectionEventClaim.COMPLETED;
            entered.countDown();
            assertTrue(releaseFailure.await(5, TimeUnit.SECONDS));
            Thread.currentThread().interrupt(); // The revoked lane has been interrupted.
            throw new CannotCreateTransactionException("late failure from revoked worker");
        };
        if (pendingReplay) {
            given(stateStore.claim(any(SecurityEvent.class), any(), any(), anyString())).willAnswer(claim);
        } else {
            given(stateStore.claim(any(SecurityEvent.class), anyString(), any(), any(), anyString())).willAnswer(claim);
        }
        KafkaEventConsumer consumer = new KafkaEventConsumer(engine, stateStore);
        configureFastRetries(consumer);
        TopicPartition partition = new TopicPartition("socp-events", 0);
        Runnable delivery = () -> {
            if (pendingReplay) {
                SecurityEvent event = new SecurityEvent("revoked-worker", Instant.EPOCH, "auth", "host",
                        "login", Map.of("tenant_id", "default"), Severity.INFO);
                consumer.processPendingWithRetry(new PendingDetectionEvent(event, 0, 10L));
            } else {
                consumer.processWithRetry(new ConsumerRecord<>("socp-events", 0, 10L, "key",
                        "{\"eventId\":\"revoked-worker\",\"tenantId\":\"default\"}"), 1L);
            }
        };
        CompletableFuture<Void> oldWorker = CompletableFuture.runAsync(delivery);
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            consumer.revokePartitions(List.of(partition));
            delivery.run(); // New assignment progresses before the old call fails.
            releaseFailure.countDown();
            oldWorker.get(2, TimeUnit.SECONDS);
            KafkaConsumer<String, String> kafka = mock(KafkaConsumer.class);
            given(kafka.assignment()).willReturn(java.util.Set.of(partition));
            ReflectionTestUtils.invokeMethod(consumer, "applyPauseState", kafka);
            verify(kafka, never()).pause(any());
            assertEquals(2, claims.get(), "retired work must not retry in the next assignment");
            assertEquals(pendingReplay ? 0 : 1, completionsOf(consumer).size());
        } finally {
            releaseFailure.countDown();
            consumer.stop();
        }
    }

    @Test
    @Timeout(20)
    void lateDeadLetterFailureCannotPauseAReassignedPartition() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch releaseFailure = new CountDownLatch(1);
        KafkaEventConsumer consumer = new KafkaEventConsumer(engine, stateStore);
        configureFastRetries(consumer);
        consumer.setDlqSink((id, raw) -> {
            entered.countDown();
            try {
                assertTrue(releaseFailure.await(5, TimeUnit.SECONDS));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            Thread.currentThread().interrupt();
            throw new IllegalStateException("late broker failure");
        });
        TopicPartition partition = new TopicPartition("socp-events", 0);
        CompletableFuture<Void> oldWorker = CompletableFuture.runAsync(() -> consumer.processWithRetry(
                new ConsumerRecord<>("socp-events", 0, 10L, "key", "{bad-json"), 1L));
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            consumer.revokePartitions(List.of(partition));
            releaseFailure.countDown();
            oldWorker.get(2, TimeUnit.SECONDS);
            KafkaConsumer<String, String> kafka = mock(KafkaConsumer.class);
            given(kafka.assignment()).willReturn(java.util.Set.of(partition));
            ReflectionTestUtils.invokeMethod(consumer, "applyPauseState", kafka);
            verify(kafka, never()).pause(any());
            assertEquals(0, completionsOf(consumer).size(), "a revoked handoff cannot acknowledge the record");
        } finally {
            releaseFailure.countDown();
            consumer.stop();
        }
    }

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
        given(stateStore.claim(any(SecurityEvent.class), anyString(), any(), any(), anyString()))
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
    void aPoisonRecordWaitsForDlqRecoveryInTheSameConsumerSession() throws Exception {
        KafkaEventConsumer consumer = new KafkaEventConsumer(engine);
        configureFastRetries(consumer);
        ReflectionTestUtils.setField(consumer, "dlqHandoffMaxAttempts", 1);
        AtomicInteger publishAttempts = new AtomicInteger();
        List<Map.Entry<String, String>> dlq = new ArrayList<>();
        consumer.setDlqSink((eventId, raw) -> {
            if (publishAttempts.incrementAndGet() <= 2) {
                throw new IllegalStateException("broker unreachable");
            }
            dlq.add(new AbstractMap.SimpleEntry<>(eventId, raw));
        });
        ConsumerRecord<String, String> record =
                new ConsumerRecord<>("socp-events", 3, 9L, "bad-key", "{not-json");

        consumer.processWithRetry(record, 3L);

        assertEquals(3, publishAttempts.get());
        assertEquals(1, dlq.size());
        assertEquals(1, completionsOf(consumer).size(),
                "DLQ recovery must finish the original hand-off without rebalance");
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
    void dependencyFailurePastAttemptBudgetRecoversWithoutDeadLettering() throws Exception {
        given(stateStore.claim(any(SecurityEvent.class), anyString(), any(), any(), anyString()))
                .willReturn(DetectionEventClaim.NEW);
        CompletableFuture<Void> rollback1 = CompletableFuture.failedFuture(
                new CompletionException(new CannotCreateTransactionException("sink transaction rollback")));
        CompletableFuture<Void> rollback2 = CompletableFuture.failedFuture(
                new CompletionException(new CannotCreateTransactionException("sink transaction rollback")));
        given(engine.ingestFromKafkaAndAwait(any(SecurityEvent.class), anyString(), any(), any()))
                .willReturn(rollback1, rollback2, CompletableFuture.completedFuture(null));
        KafkaEventConsumer consumer = new KafkaEventConsumer(engine, stateStore);
        configureFastRetries(consumer);
        List<Map.Entry<String, String>> dlq = new ArrayList<>();
        consumer.setDlqSink((eventId, raw) -> dlq.add(new AbstractMap.SimpleEntry<>(eventId, raw)));
        ConsumerRecord<String, String> record = new ConsumerRecord<>("socp-events", 3, 12L, "key-1",
                "{\"eventId\":\"consumer-test-301\",\"tenantId\":\"default\",\"source\":\"auth\","
                        + "\"host\":\"web-1\",\"msg\":\"login failed\"}");

        consumer.processWithRetry(record, 3L);

        verify(engine, times(3)).ingestFromKafkaAndAwait(
                any(SecurityEvent.class), anyString(), any(), any());
        assertEquals(List.of(), dlq, "dependency failures must never consume the poison budget");
        verify(stateStore, never()).recordDeadLettered(anyString(), anyString(), any(), any(), anyString());
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
    @Timeout(20)
    void pendingReplaySurvivesClaimDatabaseFailurePastAttemptBudget() {
        given(stateStore.claim(any(SecurityEvent.class), any(), any(), anyString()))
                .willThrow(new DataAccessResourceFailureException("journal database unavailable"))
                .willThrow(new DataAccessResourceFailureException("journal database unavailable"))
                .willReturn(DetectionEventClaim.PENDING);
        given(engine.ingestFromKafkaAndAwait(any(SecurityEvent.class), any(), any()))
                .willReturn(CompletableFuture.completedFuture(null));
        KafkaEventConsumer consumer = new KafkaEventConsumer(engine, stateStore);
        configureFastRetries(consumer);
        List<Map.Entry<String, String>> dlq = new ArrayList<>();
        consumer.setDlqSink((eventId, raw) -> dlq.add(new AbstractMap.SimpleEntry<>(eventId, raw)));
        SecurityEvent event = new SecurityEvent(Instant.now(), "auth", "web-1", "replay target",
                Map.of("tenantId", "default", "src_ip", "198.51.100.7"), Severity.INFO);

        consumer.processPendingWithRetry(new PendingDetectionEvent(event, 3, 21L));

        assertEquals(List.of(), dlq);
        verify(stateStore, times(3)).claim(any(SecurityEvent.class), eq(3), eq(21L), anyString());
        verify(engine).ingestFromKafkaAndAwait(any(SecurityEvent.class), eq(3), eq(21L));
        verify(stateStore).markCompleted(any(SecurityEvent.class));
    }

    @Test
    @Timeout(20)
    void markCompletedFailureRetriesOnlyFinalizationAndNeverDeadLetters() throws Exception {
        given(stateStore.claim(any(SecurityEvent.class), anyString(), any(), any(), anyString()))
                .willReturn(DetectionEventClaim.NEW);
        given(engine.ingestFromKafkaAndAwait(any(SecurityEvent.class), anyString(), any(), any()))
                .willReturn(CompletableFuture.completedFuture(null));
        org.mockito.Mockito.doThrow(new DataAccessResourceFailureException("journal commit failed"))
                .doNothing()
                .when(stateStore).markCompleted(any(SecurityEvent.class));

        KafkaEventConsumer consumer = new KafkaEventConsumer(engine, stateStore);
        configureFastRetries(consumer);
        List<Map.Entry<String, String>> dlq = new ArrayList<>();
        consumer.setDlqSink((eventId, raw) -> dlq.add(new AbstractMap.SimpleEntry<>(eventId, raw)));
        ConsumerRecord<String, String> record = new ConsumerRecord<>("socp-events", 3, 12L,
                "default|src_ip|198.51.100.7",
                "{\"eventId\":\"finalize-retry-1\",\"tenantId\":\"default\",\"source\":\"auth\","
                        + "\"host\":\"web-1\",\"msg\":\"login failed\","
                        + "\"fields\":{\"src_ip\":\"198.51.100.7\"}}");

        consumer.processWithRetry(record, 3L);

        assertEquals(List.of(), dlq);
        verify(engine, times(1)).ingestFromKafkaAndAwait(
                any(SecurityEvent.class), anyString(), any(), any());
        verify(stateStore, times(2)).markCompleted(any(SecurityEvent.class));
        assertEquals(1, completionsOf(consumer).size());
    }

    @Test
    @Timeout(30)
    void typedWorkerFailuresRecoverWithoutDlqOrFullStateRebuild() throws Exception {
        List<Throwable> transientFailures = List.of(
                new TenantAdmission.RejectedException("default", TenantAdmission.RejectionReason.RATE),
                new TenantAdmission.RejectedException("default",
                        TenantAdmission.RejectionReason.PENDING_BYTES),
                new DetectEngineService.RuntimeUnavailableException(
                        "detection state recovery is DEGRADED"),
                new CannotCreateTransactionException("durable database unavailable"));

        for (Throwable transientFailure : transientFailures) {
            org.mockito.Mockito.reset(engine, stateStore);
            given(stateStore.claim(any(SecurityEvent.class), anyString(), any(), any(), anyString()))
                    .willReturn(DetectionEventClaim.NEW);
            given(engine.ingestFromKafkaAndAwait(any(SecurityEvent.class), anyString(), any(), any()))
                    .willReturn(CompletableFuture.failedFuture(transientFailure),
                            CompletableFuture.completedFuture(null));

            KafkaEventConsumer consumer = new KafkaEventConsumer(engine, stateStore);
            configureFastRetries(consumer);
            List<Map.Entry<String, String>> dlq = new ArrayList<>();
            consumer.setDlqSink((eventId, raw) -> dlq.add(new AbstractMap.SimpleEntry<>(eventId, raw)));
            ConsumerRecord<String, String> record = new ConsumerRecord<>("socp-events", 3, 40L,
                    "default|src_ip|198.51.100.7",
                    "{\"eventId\":\"unavailable-1\",\"tenantId\":\"default\",\"source\":\"auth\","
                            + "\"host\":\"web-1\",\"msg\":\"login failed\","
                            + "\"fields\":{\"src_ip\":\"198.51.100.7\"}}");

            consumer.processWithRetry(record, 3L);

            assertEquals(List.of(), dlq,
                    "transient failure " + transientFailure + " must not dead-letter");
            assertEquals(1, completionsOf(consumer).size());
            verify(engine, times(2)).ingestFromKafkaAndAwait(
                    any(SecurityEvent.class), anyString(), any(), any());
            verify(engine, never()).rebuildForPartitions(any());
            verify(stateStore, never()).recordDeadLettered(
                    anyString(), anyString(), any(), any(), anyString());
        }
    }

    @Test
    @Timeout(30)
    void pendingReplayRecoversInTheSameSessionAfterRuntimeRecovery() {
        given(stateStore.claim(any(SecurityEvent.class), any(), any(), anyString()))
                .willReturn(DetectionEventClaim.PENDING);
        given(engine.ingestFromKafkaAndAwait(any(SecurityEvent.class), any(), any()))
                .willReturn(CompletableFuture.failedFuture(
                                new DetectEngineService.RuntimeUnavailableException(
                                        "detection state recovery is RECOVERING")),
                        CompletableFuture.completedFuture(null));
        KafkaEventConsumer consumer = new KafkaEventConsumer(engine, stateStore);
        configureFastRetries(consumer);
        List<Map.Entry<String, String>> dlq = new ArrayList<>();
        consumer.setDlqSink((eventId, raw) -> dlq.add(new AbstractMap.SimpleEntry<>(eventId, raw)));
        SecurityEvent event = new SecurityEvent(Instant.now(), "auth", "web-1", "replay target",
                Map.of("tenantId", "default", "src_ip", "198.51.100.7"), Severity.INFO);

        consumer.processPendingWithRetry(new PendingDetectionEvent(event, 3, 41L));

        assertEquals(List.of(), dlq);
        verify(engine, times(2)).ingestFromKafkaAndAwait(any(SecurityEvent.class), eq(3), eq(41L));
        verify(stateStore, never()).recordDeadLettered(anyString(), anyString(), any(), any(), anyString());
        verify(stateStore).markCompleted(any(SecurityEvent.class));
    }

    @Test
    @Timeout(20)
    void liveClaimDatabaseFailureRecoversAfterOriginalDlqWindow() throws Exception {
        given(stateStore.claim(any(SecurityEvent.class), anyString(), any(), any(), anyString()))
                .willThrow(new DataAccessResourceFailureException("postgres unavailable"))
                .willThrow(new DataAccessResourceFailureException("postgres unavailable"))
                .willReturn(DetectionEventClaim.NEW);
        given(engine.ingestFromKafkaAndAwait(any(SecurityEvent.class), anyString(), any(), any()))
                .willReturn(CompletableFuture.completedFuture(null));

        KafkaEventConsumer consumer = new KafkaEventConsumer(engine, stateStore);
        configureFastRetries(consumer);
        List<Map.Entry<String, String>> dlq = new ArrayList<>();
        consumer.setDlqSink((eventId, raw) -> dlq.add(new AbstractMap.SimpleEntry<>(eventId, raw)));
        ConsumerRecord<String, String> record = new ConsumerRecord<>("socp-events", 2, 50L, "key",
                "{\"eventId\":\"claim-recovery-1\",\"tenantId\":\"default\","
                        + "\"source\":\"auth\",\"host\":\"web-1\",\"msg\":\"ok\"}");

        consumer.processWithRetry(record, 8L);

        verify(stateStore, times(3)).claim(any(SecurityEvent.class), eq("socp-events"), eq(2), eq(50L), anyString());
        verify(engine).ingestFromKafkaAndAwait(
                any(SecurityEvent.class), anyString(), eq(2), eq(50L));
        assertEquals(List.of(), dlq);
        assertEquals(1, completionsOf(consumer).size());
    }

    @Test
    @Timeout(20)
    void ownershipLossStopsOldEpochBeforeJournalCompletion() throws Exception {
        given(stateStore.claim(any(SecurityEvent.class), anyString(), any(), any(), anyString()))
                .willReturn(DetectionEventClaim.NEW);
        given(engine.ingestFromKafkaAndAwait(any(SecurityEvent.class), anyString(), any(), any()))
                .willReturn(CompletableFuture.completedFuture(null));
        org.mockito.Mockito.doThrow(new DetectionStateOwnership.StaleStateOwnerException(
                        "Kafka partition is no longer assigned: 3"))
                .when(engine).assertCurrentOwner(any(SecurityEvent.class), eq(3));

        KafkaEventConsumer consumer = new KafkaEventConsumer(engine, stateStore);
        configureFastRetries(consumer);
        ConsumerRecord<String, String> record = new ConsumerRecord<>("socp-events", 3, 51L, "key",
                "{\"eventId\":\"stale-owner-1\",\"tenantId\":\"default\","
                        + "\"source\":\"auth\",\"host\":\"web-1\",\"msg\":\"ok\"}");

        consumer.processWithRetry(record, 9L);

        verify(stateStore, never()).markCompleted(any(SecurityEvent.class));
        assertEquals(0, completionsOf(consumer).size());
        assertTrue(((java.util.concurrent.atomic.AtomicBoolean)
                ReflectionTestUtils.getField(consumer, "sessionRestartRequested")).get());
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void completedLaterOffsetCannotCommitAcrossEarlierGap() throws Exception {
        given(stateStore.claim(any(SecurityEvent.class), anyString(), any(), any(), anyString()))
                .willReturn(DetectionEventClaim.NEW);
        given(engine.ingestFromKafkaAndAwait(any(SecurityEvent.class), anyString(), any(), any()))
                .willReturn(CompletableFuture.completedFuture(null));
        KafkaEventConsumer consumer = new KafkaEventConsumer(engine, stateStore);
        configureFastRetries(consumer);

        Field trackerField = KafkaEventConsumer.class.getDeclaredField("completionTracker");
        trackerField.setAccessible(true);
        PartitionCompletionTracker tracker = (PartitionCompletionTracker) trackerField.get(consumer);
        long gapEpoch = tracker.register(3, 60L);
        long laterEpoch = tracker.register(3, 61L);

        ConsumerRecord<String, String> later = new ConsumerRecord<>("socp-events", 3, 61L, "key",
                "{\"eventId\":\"later-61\",\"tenantId\":\"default\","
                        + "\"source\":\"auth\",\"host\":\"web-1\",\"msg\":\"later\"}");
        consumer.processWithRetry(later, laterEpoch);

        KafkaConsumer<String, String> kafka = mock(KafkaConsumer.class);
        Method drain = KafkaEventConsumer.class.getDeclaredMethod("drainCompletions", KafkaConsumer.class);
        drain.setAccessible(true);
        drain.invoke(consumer, kafka);
        verify(kafka, never()).commitSync(any(Map.class));

        ConsumerRecord<String, String> gap = new ConsumerRecord<>("socp-events", 3, 60L, "key",
                "{\"eventId\":\"gap-60\",\"tenantId\":\"default\","
                        + "\"source\":\"auth\",\"host\":\"web-1\",\"msg\":\"gap\"}");
        consumer.processWithRetry(gap, gapEpoch);
        drain.invoke(consumer, kafka);

        ArgumentCaptor<Map> commits = ArgumentCaptor.forClass(Map.class);
        verify(kafka).commitSync(commits.capture());
        Map<TopicPartition, OffsetAndMetadata> committed = commits.getValue();
        assertEquals(62L, committed.get(new TopicPartition("socp-events", 3)).offset());
    }

    private static void configureFastRetries(KafkaEventConsumer consumer) {
        ReflectionTestUtils.setField(consumer, "processingMaxAttempts", 1);
        ReflectionTestUtils.setField(consumer, "processingRetryInitialDelayMs", 1L);
        ReflectionTestUtils.setField(consumer, "dlqHandoffRetryDelayMs", 1L);
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
        given(kafka.assignment()).willReturn(java.util.Set.of(partition));
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
        Method applyPauseState = KafkaEventConsumer.class.getDeclaredMethod(
                "applyPauseState", KafkaConsumer.class);
        applyPauseState.setAccessible(true);
        applyPauseState.invoke(consumer, kafka);
        verify(kafka).pause(eq(java.util.Set.of(partition)));

        releaseFirst.countDown();
        assertTrue(secondFinished.await(2, TimeUnit.SECONDS));
        Method drain = KafkaEventConsumer.class.getDeclaredMethod("drainDeferred", KafkaConsumer.class);
        drain.setAccessible(true);
        drain.invoke(consumer, kafka);
        applyPauseState.invoke(consumer, kafka);

        verify(kafka).resume(eq(java.util.Set.of(partition)));
        consumer.stop();
    }

    @Test
    void byteBudgetPausesAQuietPartitionBeforeAdmittingAnOversizedWorkItem() throws Exception {
        KafkaEventConsumer consumer = new KafkaEventConsumer(engine);
        KafkaConsumer<String, String> kafka = mock(KafkaConsumer.class);
        TopicPartition partition = new TopicPartition("events", 7);
        given(kafka.assignment()).willReturn(java.util.Set.of(partition));
        CountDownLatch completed = new CountDownLatch(1);

        Field budget = KafkaEventConsumer.class.getDeclaredField("partitionMaxPendingBytes");
        budget.setAccessible(true);
        budget.setLong(consumer, 4L);
        Method dispatch = KafkaEventConsumer.class.getDeclaredMethod(
                "dispatchOrDefer", KafkaConsumer.class, TopicPartition.class, Runnable.class, long.class);
        dispatch.setAccessible(true);
        dispatch.invoke(consumer, kafka, partition, (Runnable) completed::countDown, 32L);
        Method applyPauseState = KafkaEventConsumer.class.getDeclaredMethod(
                "applyPauseState", KafkaConsumer.class);
        applyPauseState.setAccessible(true);
        applyPauseState.invoke(consumer, kafka);

        verify(kafka).pause(eq(java.util.Set.of(partition)));
        assertTrue(!completed.await(100, TimeUnit.MILLISECONDS));

        Method drain = KafkaEventConsumer.class.getDeclaredMethod("drainDeferred", KafkaConsumer.class);
        drain.setAccessible(true);
        drain.invoke(consumer, kafka);
        applyPauseState.invoke(consumer, kafka);

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
