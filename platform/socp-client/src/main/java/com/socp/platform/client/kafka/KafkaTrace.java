package com.socp.platform.client.kafka;

import com.socp.platform.obs.trace.TracePropagation;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.context.propagation.TextMapSetter;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.Headers;
import org.slf4j.MDC;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * Kafka trace-context propagation for the canonical event path.
 *
 * <p>A Kafka hop is asynchronous and usually runs in a thread that never saw
 * the producing request, so nothing about the caller survives except what was
 * written into the record headers. Reading {@code traceparent} into a
 * {@link Context} and starting the consumer span with it as the parent is what
 * makes the consumer a real child span; putting the trace-id into the MDC only
 * makes both sides print the same string, and no exporter can join two spans
 * that merely share an identifier.
 */
public final class KafkaTrace {

    public static final String TRACEPARENT = TracePropagation.TRACEPARENT;

    private static final TextMapGetter<Headers> GETTER = new TextMapGetter<>() {
        @Override
        public Iterable<String> keys(Headers headers) {
            List<String> names = new ArrayList<>();
            if (headers != null) {
                for (Header header : headers) {
                    names.add(header.key());
                }
            }
            return names;
        }

        @Override
        public String get(Headers headers, String key) {
            if (headers == null) {
                return null;
            }
            Header header = headers.lastHeader(key);
            return header == null || header.value() == null
                    ? null
                    : new String(header.value(), StandardCharsets.UTF_8);
        }
    };

    private static final TextMapSetter<Headers> SETTER = (headers, key, value) -> {
        // Replace rather than append: a retried publish would otherwise stack
        // duplicate headers and force every reader to remember the last wins.
        headers.remove(key);
        headers.add(key, value.getBytes(StandardCharsets.UTF_8));
    };

    private KafkaTrace() {
    }

    /** The producing side's context as carried by the record headers. */
    public static Context extract(Headers headers) {
        return TracePropagation.extract(GETTER, headers);
    }

    /** Writes {@code context} onto the record so the consumer can join it. */
    public static void inject(Context context, Headers headers) {
        TracePropagation.inject(context, SETTER, headers);
    }

    /** Writes the currently active span onto the record, if there is one. */
    public static void injectCurrent(Headers headers) {
        inject(Context.current(), headers);
    }

    /**
     * Opens a CONSUMER span parented to the record's {@code traceparent} for
     * call sites that must manage the lifecycle themselves, for example because
     * the work throws a checked exception. Pair with {@link #contextOf} and
     * {@link TracePropagation#finish}.
     */
    public static Span startConsume(String spanName, Headers headers) {
        return TracePropagation.startSpan(spanName, SpanKind.CONSUMER, extract(headers));
    }

    /** The context carrying {@code span}, for making it current or injecting it. */
    public static Context contextOf(Span span) {
        return Context.current().with(span);
    }

    /**
     * Runs {@code work} inside a CONSUMER span parented to the record's
     * {@code traceparent}, and mirrors the identifiers into the MDC so log
     * correlation keeps working when no SDK is installed and no span exists.
     */
    public static void runConsumed(String spanName, Headers headers, Runnable work) {
        callConsumed(spanName, headers, () -> {
            work.run();
            return null;
        });
    }

    /**
     * Value-returning form of {@link #runConsumed} for consumers that process a
     * batch and must hand a result back to the polling loop.
     */
    public static <T> T callConsumed(String spanName, Headers headers, Supplier<T> work) {
        Context parent = extract(headers);
        Span span = TracePropagation.startSpan(spanName, SpanKind.CONSUMER, parent);
        Context spanContext = Context.current().with(span);

        String traceId = TracePropagation.traceId(spanContext);
        String traceparent = TracePropagation.traceparent(spanContext);
        if (traceId == null) {
            traceId = traceIdOf(readHeader(headers));
        }
        if (traceparent == null) {
            traceparent = readHeader(headers);
        }

        Throwable failure = null;
        try (Scope scope = spanContext.makeCurrent()) {
            if (traceId != null) {
                MDC.put("traceId", traceId);
            }
            if (traceparent != null) {
                MDC.put("traceparent", traceparent);
            }
            return work.get();
        } catch (Throwable t) {
            failure = t;
            throw t;
        } finally {
            TracePropagation.finish(span, failure);
            MDC.remove("traceparent");
            MDC.remove("traceId");
        }
    }

    private static String readHeader(Headers headers) {
        Header header = headers == null ? null : headers.lastHeader(TRACEPARENT);
        if (header == null || header.value() == null) {
            return null;
        }
        String value = new String(header.value(), StandardCharsets.UTF_8).trim();
        return value.isBlank() ? null : value;
    }

    /** Second field of a W3C {@code traceparent}, or null when it is not valid. */
    private static String traceIdOf(String traceparent) {
        if (traceparent == null) {
            return null;
        }
        String[] parts = traceparent.split("-");
        if (parts.length < 3) {
            return null;
        }
        String value = parts[1];
        if (!value.matches("[0-9a-fA-F]{32}") || value.chars().allMatch(ch -> ch == '0')) {
            return null;
        }
        return value.toLowerCase(Locale.ROOT);
    }
}
