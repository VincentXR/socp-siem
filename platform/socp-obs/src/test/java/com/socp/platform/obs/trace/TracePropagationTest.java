package com.socp.platform.obs.trace;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import com.socp.platform.obs.testsupport.RecordingExporter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Asserts on exported span data rather than on MDC contents: exported data is
 * what a collector actually receives, and it is the only way to observe that a
 * hop formed a real parent/child relationship instead of two spans sharing a
 * trace-id string.
 */
class TracePropagationTest {

    private final RecordingExporter exporter = new RecordingExporter();
    private SdkTracerProvider tracerProvider;

    private static final TextMapGetter<Map<String, String>> MAP_GETTER = new TextMapGetter<>() {
        @Override
        public Iterable<String> keys(Map<String, String> carrier) {
            return carrier.keySet();
        }

        @Override
        public String get(Map<String, String> carrier, String key) {
            return carrier.get(key);
        }
    };

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
    }

    @Test
    void traceparentNamesTheCurrentSpan() {
        Span span = TracePropagation.startSpan("ingest", SpanKind.SERVER, Context.root());

        String traceparent;
        try (Scope ignored = TracePropagation.contextWith(span).makeCurrent()) {
            // startSpan alone does not make the span current; the request scope
            // does, which is where buildTraceparent() reads it from.
            traceparent = TracePropagation.currentTraceparent();
        }
        span.end();

        assertThat(traceparent).startsWith("00-" + span.getSpanContext().getTraceId()
                + "-" + span.getSpanContext().getSpanId() + "-");
    }

    @Test
    void contextFromRebuildsAContextThatNamesTheOriginalSpan() {
        Span original = TracePropagation.startSpan("ingest", SpanKind.SERVER, Context.root());
        String stored = TracePropagation.traceparent(TracePropagation.contextWith(original));
        original.end();

        Context rebuilt = TracePropagation.contextFrom(stored);
        Span child = TracePropagation.startSpan("publish", SpanKind.PRODUCER, rebuilt);
        child.end();

        SpanData childData = only("publish");
        assertThat(childData.getParentSpanId()).isEqualTo(original.getSpanContext().getSpanId());
        assertThat(childData.getTraceId()).isEqualTo(original.getSpanContext().getTraceId());
    }

    @Test
    void contextFromToleratesMissingAndMalformedValues() {
        assertThat(TracePropagation.contextFrom(null)).isEqualTo(Context.root());
        assertThat(TracePropagation.contextFrom("   ")).isEqualTo(Context.root());
        assertThat(TracePropagation.contextFrom("not-a-traceparent")).isEqualTo(Context.root());
    }

    @Test
    void traceIdIsNullWhenTheContextCarriesNoValidSpan() {
        assertThat(TracePropagation.traceId(Context.root())).isNull();
        assertThat(TracePropagation.traceId(null)).isNull();
        assertThat(TracePropagation.traceparent(Context.root())).isNull();
        assertThat(TracePropagation.currentTraceparent()).isNull();
    }

    @Test
    void finishRecordsTheFailureAndEndsTheSpan() {
        Span span = TracePropagation.startSpan("consume", SpanKind.CONSUMER, Context.root());
        RuntimeException failure = new IllegalStateException("record rejected");

        TracePropagation.finish(span, failure);

        SpanData data = only("consume");
        assertThat(data.getStatus().getStatusCode()).isEqualTo(io.opentelemetry.api.trace.StatusCode.ERROR);
        assertThat(data.getEvents()).hasSize(1);
        assertThat(data.getEvents().get(0).getName()).isEqualTo("exception");
    }

    @Test
    void finishToleratesANullSpan() {
        TracePropagation.finish(null, new RuntimeException("ignored"));
        assertThat(exporter.spans()).isEmpty();
    }

    @Test
    void extractDefaultsToTheCurrentContext() {
        Span parent = TracePropagation.startSpan("parent", SpanKind.SERVER, Context.root());
        String stored = TracePropagation.traceparent(TracePropagation.contextWith(parent));
        Map<String, String> carrier = Map.of("traceparent", stored);

        // The single-argument form must default to the caller's context, which
        // is how a filter hands an inbound traceparent to the next span.
        Context extracted = TracePropagation.extract(MAP_GETTER, carrier);
        Span child = TracePropagation.startSpan("child", SpanKind.INTERNAL, extracted);
        child.end();
        parent.end();

        assertThat(only("child").getParentSpanId()).isEqualTo(parent.getSpanContext().getSpanId());
    }

    @Test
    void extractAcceptsAnExplicitParent() {
        Span parent = TracePropagation.startSpan("parent", SpanKind.SERVER, Context.root());
        Map<String, String> carrier = Map.of("traceparent",
                TracePropagation.traceparent(TracePropagation.contextWith(parent)));

        Span child = TracePropagation.startSpan("child", SpanKind.INTERNAL,
                TracePropagation.extract(Context.root(), MAP_GETTER, carrier));
        child.end();
        parent.end();

        assertThat(only("child").getParentSpanId()).isEqualTo(parent.getSpanContext().getSpanId());
    }

    private SpanData only(String name) {
        List<SpanData> matches = new ArrayList<>();
        for (SpanData span : exporter.spans()) {
            if (name.equals(span.getName())) {
                matches.add(span);
            }
        }
        assertThat(matches).as("exported spans named %s", name).hasSize(1);
        return matches.get(0);
    }

}
