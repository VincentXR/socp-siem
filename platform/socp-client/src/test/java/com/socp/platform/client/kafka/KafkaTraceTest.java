package com.socp.platform.client.kafka;

import com.socp.platform.obs.trace.TracePropagation;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import org.apache.kafka.common.header.Headers;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves that a Kafka hop produces a parent/child relationship rather than two
 * spans that happen to print the same trace id.
 *
 * <p>Log correlation and tracing are different claims. Copying a trace id into
 * the MDC makes both sides print one string, but no exporter can join two spans
 * that only share an identifier, so the hop is invisible in the trace tree.
 * These tests assert on exported span data, which is what a collector would
 * actually receive.
 */
class KafkaTraceTest {

    private static final String VALID_TRACEPARENT =
            "00-0123456789abcdef0123456789abcdef-0123456789abcdef-01";

    private final RecordingExporter exporter = new RecordingExporter();
    private SdkTracerProvider tracerProvider;

    @BeforeEach
    void installTracingSdk() {
        GlobalOpenTelemetry.resetForTest();
        tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build();
        OpenTelemetry sdk = OpenTelemetrySdk.builder()
                .setTracerProvider(tracerProvider)
                .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
                .build();
        GlobalOpenTelemetry.set(sdk);
    }

    @AfterEach
    void uninstallTracingSdk() {
        tracerProvider.close();
        GlobalOpenTelemetry.resetForTest();
        MDC.clear();
    }

    @Test
    void consumerSpanIsAChildOfTheProducerSpan() {
        Headers headers = new RecordHeaders();

        // Producing side: a span for the publish, injected onto the record.
        Span publish = TracePropagation.startSpan("kafka publish socp-events",
                SpanKind.PRODUCER, Context.root());
        try (Scope ignored = KafkaTrace.contextOf(publish).makeCurrent()) {
            KafkaTrace.injectCurrent(headers);
        }
        String producedTraceId = publish.getSpanContext().getTraceId();
        String producedSpanId = publish.getSpanContext().getSpanId();
        publish.end();

        // The consumer runs later on a thread that saw none of the above.
        KafkaTrace.runConsumed("detect socp-events receive", headers, () ->
                assertThat(MDC.get("traceId")).isEqualTo(producedTraceId));

        SpanData consumer = only("detect socp-events receive");
        assertThat(consumer.getTraceId()).isEqualTo(producedTraceId);
        assertThat(consumer.getParentSpanId()).isEqualTo(producedSpanId);
    }

    @Test
    void recordHeaderNamesTheProducerSpanNotJustTheTrace() {
        Headers headers = new RecordHeaders();
        Span publish = TracePropagation.startSpan("kafka publish socp-events",
                SpanKind.PRODUCER, Context.root());
        try (Scope ignored = KafkaTrace.contextOf(publish).makeCurrent()) {
            KafkaTrace.injectCurrent(headers);
        } finally {
            publish.end();
        }

        String traceparent = new String(headers.lastHeader("traceparent").value(),
                StandardCharsets.UTF_8);
        // Flags are not asserted: the W3C trace-context flags byte carries the
        // sampled bit and, in recent revisions, a random bit, so it varies.
        assertThat(traceparent).startsWith("00-" + publish.getSpanContext().getTraceId()
                + "-" + publish.getSpanContext().getSpanId() + "-");
        assertThat(traceparent).matches("00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}");
    }

    @Test
    void storedTraceparentRejoinsTheOriginalTraceAfterTheSpanIsGone() {
        // A transactional outbox row records the context while the consuming
        // span is still current, then publishes later from another thread.
        Span publish = TracePropagation.startSpan("kafka publish socp-alarm-original",
                SpanKind.PRODUCER, Context.root());
        String stored = TracePropagation.traceparent(KafkaTrace.contextOf(publish));
        publish.end();
        assertThat(stored).isNotNull();

        Span drain = TracePropagation.startSpan("kafka publish socp-alarm-original",
                SpanKind.PRODUCER, TracePropagation.contextFrom(stored));
        drain.end();

        SpanData drainData = only("kafka publish socp-alarm-original", 1);
        assertThat(drainData.getTraceId()).isEqualTo(stored.split("-")[1]);
        assertThat(drainData.getParentSpanId()).isEqualTo(publish.getSpanContext().getSpanId());
    }

    @Test
    void aRecordWithoutATraceparentRootsItsOwnTrace() {
        // No caller to join, so the hop roots a trace of its own. It still gets
        // a span and still correlates its logs, which is more than the previous
        // behaviour managed for this case.
        KafkaTrace.runConsumed("detect socp-events receive", new RecordHeaders(), () ->
                assertThat(MDC.get("traceId")).matches("[0-9a-f]{32}"));

        SpanData consumer = only("detect socp-events receive");
        assertThat(consumer.getTraceId()).matches("[0-9a-f]{32}");
        assertThat(consumer.getParentSpanId())
                .isEqualTo(io.opentelemetry.api.trace.SpanId.getInvalid());
    }

    @Test
    void withoutAnSdkTheRecordStillCorrelatesLogs() {
        // Tracing off: no SDK, so no span and a no-op propagator. The raw header
        // must still drive log correlation, which is the behaviour operators
        // rely on in every environment that does not export spans.
        GlobalOpenTelemetry.resetForTest();
        Headers headers = headersWith(VALID_TRACEPARENT);

        KafkaTrace.runConsumed("detect socp-events receive", headers, () ->
                assertThat(MDC.get("traceId")).isEqualTo("0123456789abcdef0123456789abcdef"));

        assertThat(MDC.get("traceId")).isNull();
    }

    @Test
    void withoutAnSdkAMalformedHeaderLeavesTheMdcEmpty() {
        GlobalOpenTelemetry.resetForTest();
        Headers headers = headersWith("not-a-traceparent");

        KafkaTrace.runConsumed("detect socp-events receive", headers, () ->
                assertThat(MDC.get("traceId")).isNull());
    }

    @Test
    void workFailuresPropagateAndClearTheMdc() {
        Headers headers = headersWith(VALID_TRACEPARENT);
        IllegalStateException failure = new IllegalStateException("poison record");

        assertThatThrownBy(() -> KafkaTrace.runConsumed("detect socp-events receive", headers, () -> {
            throw failure;
        })).isSameAs(failure);

        assertThat(MDC.get("traceId")).isNull();
        assertThat(MDC.get("traceparent")).isNull();
    }

    @Test
    void startConsumeOpensASpanForCallersThatOwnTheLifecycle() {
        Span span = KafkaTrace.startConsume("alarm-register", headersWith(VALID_TRACEPARENT));

        assertThat(span.getSpanContext().getTraceId()).isEqualTo("0123456789abcdef0123456789abcdef");
        assertThat(KafkaTrace.contextOf(span)).isNotNull();
        span.end();
    }

    @Test
    void aNullCarrierIsTolerated() {
        assertThat(KafkaTrace.extract(null)).isNotNull();
    }

    private static Headers headersWith(String traceparent) {
        Headers headers = new RecordHeaders();
        headers.add("traceparent", traceparent.getBytes(StandardCharsets.UTF_8));
        return headers;
    }

    private SpanData only(String name) {
        return only(name, 0);
    }

    private SpanData only(String name, int index) {
        List<SpanData> matches = new ArrayList<>();
        for (SpanData span : exporter.spans) {
            if (name.equals(span.getName())) {
                matches.add(span);
            }
        }
        assertThat(matches).as("exported spans named %s", name).hasSizeGreaterThan(index);
        return matches.get(index);
    }

    /** Minimal in-memory exporter so no extra test dependency is needed. */
    private static final class RecordingExporter implements SpanExporter {

        private final List<SpanData> spans = new ArrayList<>();

        @Override
        public CompletableResultCode export(Collection<SpanData> exported) {
            spans.addAll(exported);
            return CompletableResultCode.ofSuccess();
        }

        @Override
        public CompletableResultCode flush() {
            return CompletableResultCode.ofSuccess();
        }

        @Override
        public CompletableResultCode shutdown() {
            return CompletableResultCode.ofSuccess();
        }
    }
}
