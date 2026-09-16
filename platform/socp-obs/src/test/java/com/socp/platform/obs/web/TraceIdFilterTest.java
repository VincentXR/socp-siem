package com.socp.platform.obs.web;
import com.socp.platform.obs.config.OTelSetup;
import com.socp.platform.obs.testsupport.RecordingExporter;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TraceIdFilterTest {

    /**
     * OTelSetup latches its decision in static fields and the OTel global may
     * only be set once per JVM, so both are reset between tests to keep the
     * manual and the SDK paths independently observable.
     */
    @AfterEach
    void resetOtelState() {
        GlobalOpenTelemetry.resetForTest();
        ReflectionTestUtils.setField(OTelSetup.class, "decided", false);
        ReflectionTestUtils.setField(OTelSetup.class, "initialized", false);
        MDC.clear();
    }

    @Test
    void parsesOnlyValidW3cTraceIds() {
        assertThat(TraceIdFilter.parseTraceId("00-0123456789ABCDEF0123456789ABCDEF-0123456789abcdef-01"))
                .isEqualTo("0123456789abcdef0123456789abcdef");
        assertThat(TraceIdFilter.parseTraceId("invalid")).isNull();
        assertThat(TraceIdFilter.parseTraceId(
                "00-00000000000000000000000000000000-0123456789abcdef-01")).isNull();
        assertThat(TraceIdFilter.parseTraceId(
                "00-0123456789abcdef0123456789abcdef-0000000000000000-01")).isNull();
        assertThat(TraceIdFilter.parseTraceId(null)).isNull();
    }

    @Test
    void manualFallbackPropagatesTraceAndClearsThreadContext() throws Exception {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("spring.application.name", "test-service")
                .withProperty("socp.obs.tracing.enabled", "false");
        TraceIdFilter filter = new TraceIdFilter(environment);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/health");
        request.addHeader(TraceIdFilter.TRACEPARENT,
                "00-0123456789abcdef0123456789abcdef-0123456789abcdef-01");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) ->
                assertThat(MDC.get("traceId")).isEqualTo("0123456789abcdef0123456789abcdef"));

        assertThat(response.getHeader(TraceIdFilter.HEADER))
                .isEqualTo("0123456789abcdef0123456789abcdef");
        assertThat(response.getHeader(TraceIdFilter.TRACEPARENT)).startsWith(
                "00-0123456789abcdef0123456789abcdef-");
        assertThat(MDC.get("traceId")).isNull();
    }

    @Test
    void generatedIdentifiersHaveProtocolLengths() {
        assertThat(TraceIdFilter.newTraceId()).matches("[0-9a-f]{32}");
        assertThat(TraceIdFilter.newSpanId()).matches("[0-9a-f]{16}");
        MDC.put("traceId", "abc");
        try {
            assertThat(TraceIdFilter.buildTraceparent()).isNull();
        } finally {
            MDC.clear();
        }
    }

    @Test
    void sdkPathCreatesAServerSpanAndPublishesItsTraceparent() throws Exception {
        RecordingExporter exporter = new RecordingExporter();
        SdkTracerProvider provider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build();
        OpenTelemetry sdk = OpenTelemetrySdk.builder()
                .setTracerProvider(provider)
                .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
                .build();
        GlobalOpenTelemetry.set(sdk);
        // Pin the setup decision so the filter takes the SDK path against this
        // provider instead of building an OTLP exporter of its own.
        ReflectionTestUtils.setField(OTelSetup.class, "decided", true);
        ReflectionTestUtils.setField(OTelSetup.class, "initialized", true);

        try {
            MockEnvironment environment = new MockEnvironment()
                    .withProperty("spring.application.name", "test-service")
                    .withProperty("socp.obs.tracing.enabled", "true");
            TraceIdFilter filter = new TraceIdFilter(environment);
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/alert-web/actuator/health");
            request.addHeader(TraceIdFilter.TRACEPARENT,
                    "00-0123456789abcdef0123456789abcdef-0123456789abcdef-01");
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilter(request, response, (req, res) -> {
                assertThat(MDC.get("traceId")).isEqualTo("0123456789abcdef0123456789abcdef");
                // buildTraceparent() must render the live span, so a stored
                // outbox value names a parent that actually exists.
                assertThat(TraceIdFilter.buildTraceparent())
                        .startsWith("00-0123456789abcdef0123456789abcdef-");
            });

            SpanData span = exporter.only("GET /alert-web/actuator/health");
            assertThat(span.getKind()).isEqualTo(SpanKind.SERVER);
            // Parented to the caller's span, not rooted locally.
            assertThat(span.getTraceId()).isEqualTo("0123456789abcdef0123456789abcdef");
            assertThat(span.getParentSpanId()).isEqualTo("0123456789abcdef");
            // The response traceparent names the span this filter created,
            // which is only true when the injection reads the span's own
            // context rather than the ambient one.
            assertThat(response.getHeader(TraceIdFilter.TRACEPARENT))
                    .isEqualTo("00-0123456789abcdef0123456789abcdef-" + span.getSpanId() + "-01");
            assertThat(response.getHeader(TraceIdFilter.HEADER))
                    .isEqualTo("0123456789abcdef0123456789abcdef");
            assertThat(MDC.get("traceId")).isNull();
        } finally {
            provider.close();
        }
    }

    @Test
    void sdkPathRecordsChainFailuresAndRethrows() throws Exception {
        RecordingExporter exporter = new RecordingExporter();
        SdkTracerProvider provider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build();
        GlobalOpenTelemetry.set(OpenTelemetrySdk.builder()
                .setTracerProvider(provider)
                .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
                .build());
        ReflectionTestUtils.setField(OTelSetup.class, "decided", true);
        ReflectionTestUtils.setField(OTelSetup.class, "initialized", true);

        try {
            TraceIdFilter filter = new TraceIdFilter(new MockEnvironment());
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/explodes");
            MockHttpServletResponse response = new MockHttpServletResponse();
            IllegalStateException boom = new IllegalStateException("handler failed");

            assertThatThrownBy(() -> filter.doFilter(request, response, (req, res) -> {
                throw boom;
            })).isSameAs(boom);

            SpanData span = exporter.only("GET /explodes");
            assertThat(span.getStatus().getStatusCode())
                    .isEqualTo(io.opentelemetry.api.trace.StatusCode.ERROR);
            assertThat(span.getEvents()).hasSize(1);
            assertThat(MDC.get("traceId")).isNull();
        } finally {
            provider.close();
        }
    }

    @Test
    void sdkPathMarksServerErrorsOnTheSpan() throws Exception {
        RecordingExporter exporter = new RecordingExporter();
        SdkTracerProvider provider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build();
        GlobalOpenTelemetry.set(OpenTelemetrySdk.builder()
                .setTracerProvider(provider)
                .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
                .build());
        ReflectionTestUtils.setField(OTelSetup.class, "decided", true);
        ReflectionTestUtils.setField(OTelSetup.class, "initialized", true);

        try {
            TraceIdFilter filter = new TraceIdFilter(new MockEnvironment());
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/failure");
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilter(request, response, (req, res) -> ((MockHttpServletResponse) res).setStatus(503));

            SpanData span = exporter.only("GET /failure");
            assertThat(span.getAttributes().get(
                    io.opentelemetry.api.common.AttributeKey.longKey("http.response.status_code")))
                    .isEqualTo(503L);
            assertThat(span.getStatus().getStatusCode())
                    .isEqualTo(io.opentelemetry.api.trace.StatusCode.ERROR);
        } finally {
            provider.close();
        }
    }

    @Test
    void invalidLegacyHeaderCannotCreateAnInvalidTraceparent() throws Exception {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("socp.obs.tracing.enabled", "false");
        TraceIdFilter filter = new TraceIdFilter(environment);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/health");
        request.addHeader(TraceIdFilter.HEADER, "0123456789abcdef");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) ->
                assertThat(MDC.get("traceId")).matches("[0-9a-f]{32}"));

        assertThat(response.getHeader(TraceIdFilter.HEADER)).matches("[0-9a-f]{32}");
        assertThat(response.getHeader(TraceIdFilter.TRACEPARENT))
                .matches("00-[0-9a-f]{32}-[0-9a-f]{16}-01");
    }
}
