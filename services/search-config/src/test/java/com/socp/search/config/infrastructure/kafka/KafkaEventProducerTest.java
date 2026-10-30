package com.socp.search.config.infrastructure.kafka;

import com.socp.search.config.config.KafkaProperties;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Callback;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * The outbox row carries a traceparent captured at ingest time, because the
 * publisher runs on a thread that never saw the producing request. These tests
 * pin the resulting record header to the same trace but to a new span, which is
 * what makes the publish its own hop instead of a copy of the ingest span.
 */
class KafkaEventProducerTest {

    private static final String STORED =
            "00-0123456789abcdef0123456789abcdef-0123456789abcdef-01";

    private KafkaProducer<String, String> kafka;

    @BeforeEach
    void installPropagatorAndProducer() {
        GlobalOpenTelemetry.resetForTest();
        GlobalOpenTelemetry.set(OpenTelemetrySdk.builder()
                .setTracerProvider(SdkTracerProvider.builder().build())
                .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
                .build());
        kafka = mock(KafkaProducer.class);
    }

    @AfterEach
    void resetOtel() {
        GlobalOpenTelemetry.resetForTest();
    }

    @Test
    void productionProducerConfigurationPassesRealKafka4Construction() {
        KafkaProperties properties = new KafkaProperties();
        properties.setBootstrap("127.0.0.1:1");
        KafkaEventProducer transport = new KafkaEventProducer(properties);
        // Only construct and close: never publish test records to a live broker.
        KafkaProducer<String, String> actual = ReflectionTestUtils.invokeMethod(transport, "producer");
        assertThat(actual).isNotNull();
        actual.close(java.time.Duration.ZERO);
    }

    @Test
    void publishInjectsAChildSpanOfTheStoredContext() throws Exception {
        KafkaEventProducer producer = producerWith(true);

        assertThat(producer.sendAndAwait("rk-1", "{\"id\":\"e-1\"}", STORED)).isTrue();

        assertThat(traceparentOf(producer)).startsWith(
                "00-0123456789abcdef0123456789abcdef-");
        assertThat(traceparentOf(producer)).isNotEqualTo(STORED);
    }

    @Test
    void publishWithoutAStoredContextStillProducesAValidHeader() throws Exception {
        KafkaEventProducer producer = producerWith(true);

        assertThat(producer.sendAndAwait("rk-1", "{\"id\":\"e-1\"}", null)).isTrue();

        assertThat(traceparentOf(producer)).matches("00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}");
    }

    @Test
    void disabledProducerDoesNotReportDelivery() {
        KafkaEventProducer producer = producerWith(false);

        assertThat(producer.sendAndAwait("rk-1", "{}", STORED)).isFalse();
    }

    @Test
    void failedSendDoesNotReportDelivery() throws Exception {
        KafkaEventProducer producer = producerWith(true);
        doAnswer(invocation -> {
            var failure = new IllegalStateException("broker unreachable");
            invocation.<Callback>getArgument(1).onCompletion(null, failure);
            return CompletableFuture.failedFuture(failure);
        }).when(kafka).send(any(ProducerRecord.class), any(Callback.class));

        assertThat(producer.sendAndAwait("rk-1", "{}", STORED)).isFalse();
    }

    @Test
    void asynchronousReceiptWaitsForTheActualCallbackAndMeasuresBothBoundaries() {
        KafkaEventProducer producer = producerWith(true);
        var registry = new SimpleMeterRegistry();
        producer.configureMetrics(registry);
        var callback = new AtomicReference<Callback>();
        doAnswer(invocation -> {
            callback.set(invocation.getArgument(1));
            return new CompletableFuture<RecordMetadata>();
        }).when(kafka).send(any(ProducerRecord.class), any(Callback.class));

        var receipt = producer.sendAsync("rk-1", "{}", STORED);

        assertThat(receipt).isNotDone();
        assertThat(registry.get("socp.ingestion.kafka.duration")
                .tags("operation", "submit", "outcome", "success").timer().count()).isEqualTo(1);
        assertThat(registry.get("socp.ingestion.kafka.duration")
                .tags("operation", "ack", "outcome", "success").timer().count()).isZero();
        callback.get().onCompletion(mock(RecordMetadata.class), null);
        assertThat(receipt.join()).isTrue();
        assertThat(registry.get("socp.ingestion.kafka.duration")
                .tags("operation", "ack", "outcome", "success").timer().count()).isEqualTo(1);
    }

    @Test
    void timedOutReceiptCannotBecomeSuccessfulWhenTheCallbackArrivesLate() throws Exception {
        KafkaProperties properties = new KafkaProperties();
        ReflectionTestUtils.setField(properties, "enabled", true);
        KafkaEventProducer producer = new KafkaEventProducer(properties) {
            @Override long acknowledgementTimeoutMillis() { return 10; }
        };
        ReflectionTestUtils.setField(producer, "producer", kafka);
        var registry = new SimpleMeterRegistry();
        producer.configureMetrics(registry);
        var callback = new AtomicReference<Callback>();
        doAnswer(invocation -> {
            callback.set(invocation.getArgument(1));
            return new CompletableFuture<RecordMetadata>();
        }).when(kafka).send(any(ProducerRecord.class), any(Callback.class));

        var receipt = producer.sendAsync("rk-1", "{}", STORED);

        assertThat(receipt.get(1, TimeUnit.SECONDS)).isFalse();
        callback.get().onCompletion(mock(RecordMetadata.class), null);
        assertThat(receipt.join()).isFalse();
        assertThat(registry.get("socp.ingestion.kafka.duration")
                .tags("operation", "ack", "outcome", "failure").timer().count()).isEqualTo(1);
        assertThat(registry.get("socp.ingestion.kafka.duration")
                .tags("operation", "ack", "outcome", "success").timer().count()).isZero();
    }

    private KafkaEventProducer producerWith(boolean enabled) {
        KafkaProperties properties = new KafkaProperties();
        ReflectionTestUtils.setField(properties, "enabled", enabled);
        KafkaEventProducer producer = new KafkaEventProducer(properties);
        ReflectionTestUtils.setField(producer, "producer", kafka);
        doAnswer(invocation -> {
            RecordMetadata metadata = mock(RecordMetadata.class);
            invocation.<Callback>getArgument(1).onCompletion(metadata, null);
            return CompletableFuture.completedFuture(metadata);
        }).when(kafka).send(any(ProducerRecord.class), any(Callback.class));
        return producer;
    }

    @SuppressWarnings("unchecked")
    private String traceparentOf(KafkaEventProducer producer) {
        ArgumentCaptor<ProducerRecord<String, String>> captor =
                ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafka).send(captor.capture(), any(Callback.class));
        return new String(captor.getValue().headers().lastHeader("traceparent").value(),
                StandardCharsets.UTF_8);
    }
}
