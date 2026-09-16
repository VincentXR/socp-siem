package com.socp.platform.obs.trace;

import com.socp.platform.obs.config.OTelSetup;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.context.propagation.TextMapPropagator;
import io.opentelemetry.context.propagation.TextMapSetter;

import java.util.HashMap;
import java.util.Map;

/**
 * Transport-agnostic trace-context propagation on top of the OpenTelemetry SDK.
 *
 * <p>Every hop in the event path (HTTP in, HTTP out, Kafka produce, Kafka
 * consume) must move a {@link Context} through a propagator rather than copy a
 * trace-id string. Copying the string keeps log correlation working but gives
 * the consumer a parent span that never existed, so the resulting spans share a
 * trace-id without forming a tree.
 *
 * <p>The propagator is the W3C trace-context propagator installed by
 * {@link OTelSetup}. When no SDK is installed the propagator is a no-op and
 * these methods degrade to returning nothing; callers must keep working in that
 * case, which is why the legacy {@code X-Trace-Id} handling still exists.
 */
public final class TracePropagation {

    public static final String TRACEPARENT = "traceparent";
    private static final String INSTRUMENTATION_NAME = "socp";
    private static final String INSTRUMENTATION_VERSION = "1.0.0";

    private static final TextMapSetter<Map<String, String>> MAP_SETTER = Map::put;

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

    private TracePropagation() {
    }

    /** The installed text-map propagator, or a no-op propagator without an SDK. */
    public static TextMapPropagator propagator() {
        return GlobalOpenTelemetry.getPropagators().getTextMapPropagator();
    }

    /** Extracts the caller's context from a carrier, defaulting to {@link Context#current()}. */
    public static <C> Context extract(TextMapGetter<C> getter, C carrier) {
        return propagator().extract(Context.current(), carrier, getter);
    }

    public static <C> Context extract(Context parent, TextMapGetter<C> getter, C carrier) {
        return propagator().extract(parent, carrier, getter);
    }

    public static <C> void inject(Context context, TextMapSetter<C> setter, C carrier) {
        propagator().inject(context, carrier, setter);
    }

    public static Tracer tracer() {
        return GlobalOpenTelemetry.getTracer(INSTRUMENTATION_NAME, INSTRUMENTATION_VERSION);
    }

    /** Binds a span to the current context so it becomes the parent of later spans. */
    public static Context contextWith(Span span) {
        return Context.current().with(span);
    }

    public static Span startSpan(String name, SpanKind kind, Context parent) {
        return tracer().spanBuilder(name).setSpanKind(kind).setParent(parent).startSpan();
    }

    /** Ends a span, recording the failure when the hop threw. */
    public static void finish(Span span, Throwable failure) {
        if (span == null) {
            return;
        }
        if (failure != null) {
            span.recordException(failure);
            span.setStatus(StatusCode.ERROR);
        }
        span.end();
    }

    /** The trace-id carried by this context, or null when it holds no valid span. */
    public static String traceId(Context context) {
        if (context == null) {
            return null;
        }
        SpanContext spanContext = Span.fromContext(context).getSpanContext();
        return spanContext.isValid() ? spanContext.getTraceId() : null;
    }

    /**
     * Renders this context as a W3C {@code traceparent}, or null when the
     * context holds no valid span. Persisting this on an outbox row is what
     * lets a later, thread-independent publish rejoin the original trace.
     */
    public static String traceparent(Context context) {
        if (context == null || traceId(context) == null) {
            return null;
        }
        Map<String, String> carrier = new HashMap<>();
        inject(context, MAP_SETTER, carrier);
        String value = carrier.get(TRACEPARENT);
        return value == null || value.isBlank() ? null : value;
    }

    /** The W3C {@code traceparent} of the currently active span, if any. */
    public static String currentTraceparent() {
        return traceparent(Context.current());
    }

    /**
     * Rebuilds a context from a stored W3C {@code traceparent}, for hops whose
     * context has to outlive the request that created it. A transactional
     * outbox row is the common case: the publisher runs later, on another
     * thread, with no live span to inherit.
     *
     * @return the extracted context, or {@link Context#root()} when the value
     *     is missing or malformed
     */
    public static Context contextFrom(String traceparent) {
        if (traceparent == null || traceparent.isBlank()) {
            return Context.root();
        }
        return propagator().extract(Context.root(), Map.of(TRACEPARENT, traceparent.trim()), MAP_GETTER);
    }
}
