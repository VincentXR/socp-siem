package com.socp.alert.service;


import com.socp.alert.config.AlertKafkaProperties;

import com.socp.platform.tenant.context.TenantContext;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;

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
}
