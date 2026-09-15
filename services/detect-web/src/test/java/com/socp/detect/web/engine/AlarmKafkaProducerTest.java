package com.socp.detect.web.engine;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

class AlarmKafkaProducerTest {

    private static final String STORED =
            "00-0123456789abcdef0123456789abcdef-0123456789abcdef-01";

    @AfterEach
    void resetOtel() {
        GlobalOpenTelemetry.resetForTest();
    }

    @Test
    void disabledProducerMustNotReportSuccessfulDelivery() {
        AlarmKafkaProducer producer = new AlarmKafkaProducer();
        ReflectionTestUtils.setField(producer, "enabled", false);

        assertFalse(producer.sendAndAwait(Map.of("id", "a-1"), "a-1"));
    }

    @Test
    void nullAlarmIsProgrammingErrorRatherThanSuccessfulDelivery() {
        AlarmKafkaProducer producer = new AlarmKafkaProducer();

        assertThrows(IllegalArgumentException.class, () -> producer.sendAndAwait(null, "a-1"));
    }

    @Test
    void publishInjectsAChildSpanOfTheDrainedOutboxContext() throws Exception {
        KafkaProducer<String, String> kafka = enabledProducer();
        AlarmKafkaProducer producer = producerWith(kafka);

        assertTrue(producer.sendAndAwait(Map.of("id", "a-1"), "a-1", STORED));

        String traceparent = traceparentOf(kafka);
        assertThat(traceparent).startsWith("00-0123456789abcdef0123456789abcdef-");
        // A new span, not the ingest span replayed verbatim.
        assertThat(traceparent).isNotEqualTo(STORED);
    }

    @Test
    void failedSendIsNotReportedAsDelivered() throws Exception {
        KafkaProducer<String, String> kafka = enabledProducer();
        given(kafka.send(any(ProducerRecord.class))).willReturn(
                CompletableFuture.failedFuture(new IllegalStateException("broker unreachable")));
        AlarmKafkaProducer producer = producerWith(kafka);

        assertFalse(producer.sendAndAwait(Map.of("id", "a-1"), "a-1", STORED));
    }

    @Test
    void fireAndForgetCapturesTheTraceBeforeTheVirtualThreadStarts() throws Exception {
        KafkaProducer<String, String> kafka = enabledProducer();
        AlarmKafkaProducer producer = producerWith(kafka);

        producer.send(Map.of("id", "a-1"), "a-1");

        // The publish runs on a virtual thread that inherits neither the MDC nor
        // the OTel context, so a header can only be present if it was captured
        // on the calling thread.
        ArgumentCaptor<ProducerRecord<String, String>> captor =
                ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafka, timeout(3000)).send(captor.capture());
        assertThat(new String(captor.getValue().headers().lastHeader("traceparent").value(),
                StandardCharsets.UTF_8))
                .matches("00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}");
    }

    private static KafkaProducer<String, String> enabledProducer() {
        GlobalOpenTelemetry.resetForTest();
        GlobalOpenTelemetry.set(OpenTelemetrySdk.builder()
                .setTracerProvider(SdkTracerProvider.builder().build())
                .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
                .build());
        KafkaProducer<String, String> kafka = mock(KafkaProducer.class);
        given(kafka.send(any(ProducerRecord.class)))
                .willReturn(CompletableFuture.completedFuture(mock(RecordMetadata.class)));
        return kafka;
    }

    private static AlarmKafkaProducer producerWith(KafkaProducer<String, String> kafka) {
        AlarmKafkaProducer producer = new AlarmKafkaProducer();
        ReflectionTestUtils.setField(producer, "enabled", true);
        ReflectionTestUtils.setField(producer, "topic", "socp-alarm-original");
        ReflectionTestUtils.setField(producer, "producer", kafka);
        return producer;
    }

    @SuppressWarnings("unchecked")
    private static String traceparentOf(KafkaProducer<String, String> kafka) {
        ArgumentCaptor<ProducerRecord<String, String>> captor =
                ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafka).send(captor.capture());
        return new String(captor.getValue().headers().lastHeader("traceparent").value(),
                StandardCharsets.UTF_8);
    }
}
