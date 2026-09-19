package com.socp.alert.service;


import com.socp.alert.config.AlertKafkaProperties;

import com.socp.platform.tenant.context.TenantContext;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRebalanceListener;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.MockedConstruction;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class AlarmEventConsumerTest {

    @Mock
    private AlarmDeliveryRegistrar registrar;

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void replayRegistersIdempotentDeliveryIntentsUnderCarriedTenant() throws Exception {
        AlarmEventConsumer consumer = new AlarmEventConsumer(registrar);
        String payload = "{\"id\":\"AL-100\",\"tenantId\":\"tenant-b\",\"severity\":\"HIGH\"}";

        consumer.registerEvent(payload);

        verify(registrar).register("tenant-b", "AL-100", payload);
    }

    @Test
    void rejectsMissingIdentityBeforeRegistration() {
        AlarmEventConsumer consumer = new AlarmEventConsumer(registrar);

        assertThrows(IllegalArgumentException.class,
                () -> consumer.registerEvent("{\"tenantId\":\"tenant-b\"}"));
    }

    @Test
    void rejectsInvalidTenantBeforeRegistration() {
        AlarmEventConsumer consumer = new AlarmEventConsumer(registrar);

        assertThrows(IllegalArgumentException.class,
                () -> consumer.registerEvent("{\"id\":\"AL-100\",\"tenantId\":\"../other\"}"));
    }

    @Test
    void acceptsLegacyTenantIdFieldAndKeepsScopeForRegistrar() throws Exception {
        AlarmEventConsumer consumer = new AlarmEventConsumer(registrar);
        org.mockito.Mockito.doAnswer(invocation -> {
            assertEquals("tenant-c", TenantContext.get());
            return null;
        }).when(registrar).register("tenant-c", "AL-101", "{\"id\":\"AL-101\",\"tenant_id\":\"tenant-c\"}");

        String payload = "{\"id\":\"AL-101\",\"tenant_id\":\"tenant-c\"}";
        consumer.registerEvent(payload);

        verify(registrar).register("tenant-c", "AL-101", payload);
    }

    @Test
    void rejectsMissingTenantAndMalformedJsonBeforeRegistration() {
        AlarmEventConsumer consumer = new AlarmEventConsumer(registrar);

        assertThrows(IllegalArgumentException.class,
                () -> consumer.registerEvent("{\"id\":\"AL-102\"}"));
        assertThrows(Exception.class, () -> consumer.registerEvent("{broken"));

        verify(registrar, never()).register(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
        assertNull(TenantContext.get());
    }

    @Test
    void aHealthyBatchIsRegisteredAndReadyToCommit() throws Exception {
        AlarmEventConsumer consumer = new AlarmEventConsumer(registrar);
        String payload = "{\"id\":\"AL-200\",\"tenantId\":\"tenant-a\"}";

        assertFalse(consumer.processBatch(batchWith(record("AL-200", payload, null))));

        verify(registrar).register("tenant-a", "AL-200", payload);
        assertNull(MDC.get("traceId"));
        assertNull(TenantContext.get());
    }

    @Test
    void aRuleBreakingRecordGoesToTheDlqAndTheBatchIsCommitable() {
        AlarmEventConsumer consumer = new AlarmEventConsumer(registrar);
        KafkaProducer<String, String> dlq = dlqAcknowledging(true);
        ReflectionTestUtils.setField(consumer, "dlqProducer", dlq);

        assertFalse(consumer.processBatch(
                batchWith(record("AL-201", "{\"tenantId\":\"tenant-a\"}", null))));

        verify(dlq).send(any(ProducerRecord.class));
        verify(registrar, never()).register(anyString(), anyString(), anyString());
    }

    @Test
    void anUnacknowledgedDeadLetterRewindsTheBatch() {
        AlarmEventConsumer consumer = new AlarmEventConsumer(registrar);
        ReflectionTestUtils.setField(consumer, "dlqProducer", dlqAcknowledging(false));

        assertTrue(consumer.processBatch(
                batchWith(record("AL-202", "{\"tenantId\":\"tenant-a\"}", null))));
    }

    @Test
    void aBatchThatMustBeRetriedIsRewoundRatherThanCommitted() {
        AlarmEventConsumer consumer = new AlarmEventConsumer(registrar);
        ReflectionTestUtils.setField(consumer, "dlqProducer", dlqAcknowledging(false));
        KafkaConsumer<String, String> kafka = mock(KafkaConsumer.class);

        consumer.applyPolledBatch(kafka,
                batchWith(record("AL-301", "{\"tenantId\":\"tenant-a\"}", null)));

        // The dead letter was never acknowledged, so the batch is the only durable
        // record of the work: rewind it and leave the offset uncommitted. Committing
        // here would drop the record on the next restart.
        verify(kafka).seek(any(TopicPartition.class), anyLong());
        verify(kafka, never()).commitSync();
    }

    @Test
    void aRegisteredBatchIsCommitted() {
        AlarmEventConsumer consumer = new AlarmEventConsumer(registrar);
        ReflectionTestUtils.setField(consumer, "dlqProducer", dlqAcknowledging(true));
        KafkaConsumer<String, String> kafka = mock(KafkaConsumer.class);

        consumer.applyPolledBatch(kafka,
                batchWith(record("AL-302", "{\"tenantId\":\"tenant-a\"}", null)));

        verify(kafka).commitSync();
        verify(kafka, never()).seek(any(TopicPartition.class), anyLong());
    }

    @Test
    void anEmptyPollCommitsNothingAndRewindsNothing() {
        AlarmEventConsumer consumer = new AlarmEventConsumer(registrar);
        KafkaConsumer<String, String> kafka = mock(KafkaConsumer.class);

        consumer.applyPolledBatch(kafka, ConsumerRecords.empty());

        verify(kafka, never()).commitSync();
        verify(kafka, never()).seek(any(TopicPartition.class), anyLong());
    }

    @Test
    void applyPolledBatchReportsARewindWhenTheBatchMustBeRetried() {
        AlarmEventConsumer consumer = new AlarmEventConsumer(registrar);
        ReflectionTestUtils.setField(consumer, "dlqProducer", dlqAcknowledging(false));
        KafkaConsumer<String, String> kafka = mock(KafkaConsumer.class);

        boolean rewound = consumer.applyPolledBatch(kafka,
                batchWith(record("AL-401", "{\"tenantId\":\"tenant-a\"}", null)));

        assertTrue(rewound, "an unacknowledged dead-letter must report a rewind for the supervisor to pace");
        verify(kafka).seek(any(TopicPartition.class), anyLong());
        verify(kafka, never()).commitSync();
    }

    @Test
    void applyPolledBatchReportsCommittedWhenTheBatchIsRegistered() {
        AlarmEventConsumer consumer = new AlarmEventConsumer(registrar);
        KafkaConsumer<String, String> kafka = mock(KafkaConsumer.class);

        boolean rewound = consumer.applyPolledBatch(kafka,
                batchWith(record("AL-402", "{\"id\":\"AL-402\",\"tenantId\":\"tenant-a\"}", null)));

        assertFalse(rewound);
        verify(kafka).commitSync();
    }

    @Test
    void aCommitFailurePropagatesSoTheSupervisorCanRestartTheSession() {
        // A revoked partition surfaces as CommitFailedException; the supervisor
        // catches it and re-joins instead of letting the thread die silently.
        AlarmEventConsumer consumer = new AlarmEventConsumer(registrar);
        KafkaConsumer<String, String> kafka = mock(KafkaConsumer.class);
        doThrow(new org.apache.kafka.clients.consumer.CommitFailedException("generation invalidated"))
                .when(kafka).commitSync();

        assertThrows(org.apache.kafka.clients.consumer.CommitFailedException.class,
                () -> consumer.applyPolledBatch(kafka,
                        batchWith(record("AL-403", "{\"id\":\"AL-403\",\"tenantId\":\"tenant-a\"}", null))));
    }

    @Test
    void theRewindBackoffIsBoundedByTheCeiling() {
        AlarmEventConsumer consumer = new AlarmEventConsumer(registrar);
        ReflectionTestUtils.setField(consumer, "retryDelayMs", 1_000L);
        ReflectionTestUtils.setField(consumer, "retryMaxMs", 30_000L);

        long delay = 1_000L;
        for (int i = 0; i < 20; i++) {
            delay = consumer.nextRetryDelayMs(delay);
            assertTrue(delay >= 1_000L && delay <= 30_000L,
                    "the retry delay must stay within [base, ceiling] so a wedged batch cannot hot-spin");
        }
        assertEquals(30_000L, delay, "the exponential backoff must converge on the ceiling and stop there");
    }

    @Test
    void aTransientFailureRewindsTheBatchWithoutDeadLetteringIt() throws Exception {
        AlarmEventConsumer consumer = new AlarmEventConsumer(registrar);
        // Unstubbed on purpose: this path must never touch the DLQ, and a stub
        // that goes unused fails the test under strict stubs.
        KafkaProducer<String, String> dlq = mock(KafkaProducer.class);
        ReflectionTestUtils.setField(consumer, "dlqProducer", dlq);
        doThrow(new IllegalStateException("database unavailable"))
                .when(registrar).register(eq("tenant-a"), eq("AL-203"), anyString());

        assertTrue(consumer.processBatch(batchWith(
                record("AL-203", "{\"id\":\"AL-203\",\"tenantId\":\"tenant-a\"}", null))));

        // A transient failure must not burn the record into the DLQ: the batch
        // is rewound so the same record is retried, not abandoned.
        verify(dlq, never()).send(any(ProducerRecord.class));
    }

    @Test
    void theRecordTraceIsRestoredForTheBatchAndClearedAfterwards() throws Exception {
        AlarmEventConsumer consumer = new AlarmEventConsumer(registrar);
        String payload = "{\"id\":\"AL-204\",\"tenantId\":\"tenant-a\"}";
        org.mockito.Mockito.doAnswer(invocation -> {
            assertEquals("0123456789abcdef0123456789abcdef", MDC.get("traceId"));
            return null;
        }).when(registrar).register(eq("tenant-a"), eq("AL-204"), eq(payload));

        assertFalse(consumer.processBatch(batchWith(record("AL-204", payload,
                "00-0123456789abcdef0123456789abcdef-0123456789abcdef-01"))));

        assertNull(MDC.get("traceId"));
    }

    @Test
    void theDeadLetterEntryCarriesTheTraceOfTheRecordItReplaces() throws Exception {
        // Tracing on, because the propagation itself is what is under test: a
        // no-op propagator would inject nothing and the assertion would pass
        // for the wrong reason.
        GlobalOpenTelemetry.resetForTest();
        GlobalOpenTelemetry.set(OpenTelemetrySdk.builder()
                .setTracerProvider(SdkTracerProvider.builder().build())
                .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
                .build());
        try {
            AlarmEventConsumer consumer = new AlarmEventConsumer(registrar);
            KafkaProducer<String, String> dlq = dlqAcknowledging(true);
            ReflectionTestUtils.setField(consumer, "dlqProducer", dlq);
            String stored = "00-0123456789abcdef0123456789abcdef-0123456789abcdef-01";

            assertFalse(consumer.processBatch(
                    batchWith(record("AL-205", "{\"tenantId\":\"tenant-a\"}", stored))));

            ArgumentCaptor<ProducerRecord<String, String>> captor =
                    ArgumentCaptor.forClass(ProducerRecord.class);
            verify(dlq).send(captor.capture());
            assertEquals(stored, new String(
                    captor.getValue().headers().lastHeader("traceparent").value(),
                    StandardCharsets.UTF_8),
                    "the DLQ entry must stay in the trace that failed");
        } finally {
            GlobalOpenTelemetry.resetForTest();
        }
    }

    private static ConsumerRecords<String, String> batchWith(ConsumerRecord<String, String>... records) {
        return new ConsumerRecords<>(Map.of(
                new TopicPartition("socp-alarm-original", 0), List.of(records)));
    }

    private static ConsumerRecord<String, String> record(String key, String value, String traceparent) {
        ConsumerRecord<String, String> record =
                new ConsumerRecord<>("socp-alarm-original", 0, 0L, key, value);
        if (traceparent != null) {
            record.headers().add("traceparent", traceparent.getBytes(StandardCharsets.UTF_8));
        }
        return record;
    }

    private static KafkaProducer<String, String> dlqAcknowledging(boolean acknowledged) {
        KafkaProducer<String, String> producer = mock(KafkaProducer.class);
        Future<RecordMetadata> delivery = acknowledged
                ? CompletableFuture.completedFuture(mock(RecordMetadata.class))
                : CompletableFuture.failedFuture(new IllegalStateException("no acknowledgement"));
        given(producer.send(any(ProducerRecord.class))).willReturn(delivery);
        return producer;
    }

    @Test
    void disabledConsumerDoesNotStartKafkaWorker() {
        AlertKafkaProperties properties = new AlertKafkaProperties();
        properties.setEnabled(false);
        AlarmEventConsumer consumer = new AlarmEventConsumer(registrar, properties);

        consumer.start();

        verify(registrar, never()).register(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
    }

    // -----------------------------------------------------------------------------------
    // Session supervision. The reconciler owns the only polling thread, so a failed or
    // wedged session has to be re-opened under bounded back-off instead of taking the
    // thread (and with it the delivery registration path) down silently.
    // -----------------------------------------------------------------------------------

    @Test
    void anEnabledReconcilerReopensAFailedSessionUnderBoundedBackoff() throws Exception {
        AlertKafkaProperties properties = new AlertKafkaProperties();
        properties.setEnabled(true);
        // An empty bootstrap list fails every session at construction time, on the
        // worker thread, without touching DNS or a socket: the supervisor decision is
        // the same as for a broker that is simply down at boot.
        properties.setBootstrap("");
        AlertPerformanceMetrics metrics = mock(AlertPerformanceMetrics.class);
        List<Long> restartAt = Collections.synchronizedList(new ArrayList<>());
        doAnswer(invocation -> {
            restartAt.add(System.nanoTime());
            return null;
        }).when(metrics).reconcilerSessionRestart();
        AlarmEventConsumer consumer = new AlarmEventConsumer(registrar, properties, metrics);
        ReflectionTestUtils.setField(consumer, "retryDelayMs", 40L);
        ReflectionTestUtils.setField(consumer, "retryMaxMs", 200L);

        Thread worker;
        try {
            consumer.start();
            worker = (Thread) ReflectionTestUtils.getField(consumer, "worker");
            assertNotNull(worker, "an enabled reconciler must own a polling thread");
            assertEquals("alarm-event-consumer", worker.getName());
            assertTrue(worker.isDaemon(), "the reconciler must not keep the JVM alive on its own");
            assertTrue(waitUntil(() -> restartAt.size() >= 4, 20_000L),
                    "the supervisor must keep re-opening the session, restarts seen: " + restartAt.size());
            assertTrue(worker.isAlive(),
                    "a failing session may not kill the only polling thread while the service stays green");

            // The gaps are the back-off itself: 40ms, then 80ms, then 160ms, so the
            // first four restarts cannot happen sooner than ~280ms after the first.
            // Anything close to zero would be a hot spin against a dead broker.
            long spanMs = (restartAt.get(3) - restartAt.get(0)) / 1_000_000L;
            assertTrue(spanMs >= 200L,
                    "restarts must be paced by the doubling back-off, observed span was " + spanMs + "ms");
        } finally {
            consumer.stop();
        }

        assertTrue(waitUntil(() -> !worker.isAlive(), 10_000L),
                "stop() must release the supervised thread instead of leaving it spinning");
        assertFalse(runningLatchOf(consumer).get(),
                "stop() must clear the running latch the supervisor polls");
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void aWedgedBatchIsHeldUncommittedAndParkedAtTheRetryCeiling() {
        AlertKafkaProperties properties = new AlertKafkaProperties();
        properties.setBootstrap("");
        AlertPerformanceMetrics metrics = mock(AlertPerformanceMetrics.class);
        AlarmEventConsumer consumer = new AlarmEventConsumer(registrar, properties, metrics);
        ReflectionTestUtils.setField(consumer, "retryDelayMs", 5L);
        ReflectionTestUtils.setField(consumer, "retryMaxMs", 60L);
        ReflectionTestUtils.setField(consumer, "retryCeilingBatches", 2);
        // start() owns this latch; the supervised loop is entered directly so the pacing
        // inside one session can be observed without a broker or a second thread.
        runningLatchOf(consumer).set(true);
        doThrow(new IllegalStateException("database unavailable"))
                .when(registrar).register(anyString(), anyString(), anyString());
        ConsumerRecords<String, String> wedged =
                batchWith(record("AL-500", "{\"id\":\"AL-500\",\"tenantId\":\"tenant-a\"}", null));
        AtomicLong polls = new AtomicLong();
        List<Properties> sessions = Collections.synchronizedList(new ArrayList<>());

        long elapsedMs;
        try (MockedConstruction<KafkaConsumer> construction = Mockito.mockConstruction(KafkaConsumer.class,
                (mock, context) -> {
                    sessions.add((Properties) context.arguments().get(0));
                    given(mock.poll(any(Duration.class))).willAnswer(invocation -> {
                        if (polls.incrementAndGet() > 2) {
                            // The ceiling pause has been taken; ask for shutdown and hand
                            // back an empty batch so the session closes on its own terms.
                            consumer.stop();
                            return ConsumerRecords.empty();
                        }
                        return wedged;
                    });
                })) {
            long startedAt = System.nanoTime();
            ReflectionTestUtils.invokeMethod(consumer, "run");
            elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L;

            assertEquals(1, construction.constructed().size(),
                    "a wedged batch must be retried inside the session, not by restarting it");
            assertEquals(3, polls.get(),
                    "the reconciler must park at the ceiling instead of polling on and on");
            InOrder pacing = Mockito.inOrder(metrics);
            pacing.verify(metrics).reconcilerBackoff("retry");
            pacing.verify(metrics).reconcilerBackoff("ceiling");
            pacing.verifyNoMoreInteractions();

            KafkaConsumer<String, String> kafka = construction.constructed().get(0);
            verify(kafka).subscribe(any(Collection.class), any(ConsumerRebalanceListener.class));
            verify(kafka, times(2)).seek(any(TopicPartition.class), anyLong());
            verify(kafka, never()).commitSync();
            verify(kafka).close();
            assertTrue(elapsedMs >= 60L,
                    "the ceiling pause must really park for retryMaxMs, elapsed was " + elapsedMs + "ms");
            assertTrue(elapsedMs < 10_000L, "the ceiling back-off must stay bounded");
        }

        Properties config = sessions.getFirst();
        assertEquals("socp-alarm-delivery-registration", config.get(ConsumerConfig.GROUP_ID_CONFIG));
        assertEquals("false", config.get(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG),
                "nothing may advance without an explicit commit");
        assertEquals(String.valueOf(ReflectionTestUtils.getField(consumer, "maxPollIntervalMs")),
                config.get(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG),
                "a slow batch must not be kicked out of the group mid-retry");
        assertNull(ReflectionTestUtils.getField(consumer, "activeConsumer"),
                "the closed session must not leave a stale consumer for stop() to wake");
        assertFalse(runningLatchOf(consumer).get());
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void anUnexpectedWakeupEndsTheSessionWithoutCountingARestart() {
        AlertKafkaProperties properties = new AlertKafkaProperties();
        properties.setBootstrap("");
        AlertPerformanceMetrics metrics = mock(AlertPerformanceMetrics.class);
        AlarmEventConsumer consumer = new AlarmEventConsumer(registrar, properties, metrics);
        runningLatchOf(consumer).set(true);

        try (MockedConstruction<KafkaConsumer> construction = Mockito.mockConstruction(KafkaConsumer.class,
                (mock, context) -> given(mock.poll(any(Duration.class)))
                        .willThrow(new org.apache.kafka.common.errors.WakeupException()))) {
            ReflectionTestUtils.invokeMethod(consumer, "run");

            assertEquals(1, construction.constructed().size(),
                    "a wakeup is a shutdown signal, not a session failure to be retried");
            verifyNoInteractions(metrics);
            KafkaConsumer<String, String> kafka = construction.constructed().get(0);
            verify(kafka).close();
            verify(kafka, never()).commitSync();
        }
        assertTrue(runningLatchOf(consumer).get(), "a stray wakeup must not flip the latch by itself");
        assertNull(ReflectionTestUtils.getField(consumer, "activeConsumer"));
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void aFailedSessionIsReopenedAndTheHealthyOneResetsTheBackoff() {
        AlertKafkaProperties properties = new AlertKafkaProperties();
        properties.setBootstrap("");
        AlertPerformanceMetrics metrics = mock(AlertPerformanceMetrics.class);
        AlarmEventConsumer consumer = new AlarmEventConsumer(registrar, properties, metrics);
        // A pinned zero delay keeps the restart deterministic and still exercises the
        // "do not sleep at all" pacing branch.
        ReflectionTestUtils.setField(consumer, "retryDelayMs", 0L);
        ReflectionTestUtils.setField(consumer, "retryMaxMs", 0L);
        runningLatchOf(consumer).set(true);
        AtomicLong polls = new AtomicLong();

        try (MockedConstruction<KafkaConsumer> construction = Mockito.mockConstruction(KafkaConsumer.class,
                (mock, context) -> given(mock.poll(any(Duration.class))).willAnswer(invocation -> {
                    if (polls.incrementAndGet() == 1L) {
                        // A revoked generation surfaces as a commit failure: the session
                        // dies, the offset stays uncommitted, the supervisor re-joins.
                        throw new org.apache.kafka.clients.consumer.CommitFailedException("generation invalidated");
                    }
                    consumer.stop();
                    return ConsumerRecords.empty();
                }))) {
            ReflectionTestUtils.invokeMethod(consumer, "run");

            assertEquals(2, construction.constructed().size(),
                    "the supervisor must re-open the session instead of leaving the path dead");
            verify(metrics, times(1)).reconcilerSessionRestart();
            verify(metrics, never()).reconcilerBackoff(anyString());
            for (KafkaConsumer kafka : construction.constructed()) {
                verify(kafka).close();
                verify(kafka, never()).commitSync();
            }
        }
        assertFalse(runningLatchOf(consumer).get());
        assertNull(ReflectionTestUtils.getField(consumer, "activeConsumer"));
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void theRebalanceCallbacksOnlyObserveAndNeverAdvanceOffsets() {
        AlertKafkaProperties properties = new AlertKafkaProperties();
        properties.setBootstrap("");
        AlarmEventConsumer consumer = new AlarmEventConsumer(registrar, properties, null);
        runningLatchOf(consumer).set(true);
        AtomicBoolean handedOver = new AtomicBoolean(false);

        try (MockedConstruction<KafkaConsumer> construction = Mockito.mockConstruction(KafkaConsumer.class,
                (mock, context) -> {
                    given(mock.poll(any(Duration.class))).willAnswer(invocation -> {
                        if (handedOver.getAndSet(true)) {
                            consumer.stop();
                        }
                        return ConsumerRecords.empty();
                    });
                })) {
            ReflectionTestUtils.invokeMethod(consumer, "run");
            ArgumentCaptor<ConsumerRebalanceListener> listener =
                    ArgumentCaptor.forClass(ConsumerRebalanceListener.class);
            verify(construction.constructed().get(0))
                    .subscribe(any(Collection.class), listener.capture());

            TopicPartition assigned = new TopicPartition("socp-alarm-events", 3);
            // Revocation and assignment are pure observation: committing or rewinding here
            // would race the poll loop that owns the offsets.
            listener.getValue().onPartitionsRevoked(List.of(assigned));
            listener.getValue().onPartitionsAssigned(List.of(assigned));
            listener.getValue().onPartitionsRevoked(List.of());
            listener.getValue().onPartitionsAssigned(List.of());

            verify(construction.constructed().get(0), never()).commitSync();
            verify(construction.constructed().get(0), never()).commitAsync();
            verify(construction.constructed().get(0), never()).seek(any(TopicPartition.class), anyLong());
            verify(construction.constructed().get(0)).close();
        }
        verifyNoInteractions(registrar);
    }

    private static AtomicBoolean runningLatchOf(AlarmEventConsumer consumer) {
        return (AtomicBoolean) ReflectionTestUtils.getField(consumer, "running");
    }

    private static boolean waitUntil(BooleanSupplier condition, long timeoutMs) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) return true;
            Thread.sleep(10L);
        }
        return condition.getAsBoolean();
    }
}
