package com.socp.platform.obs;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class TraceIdFilterTest {

    @Test
    void parsesOnlyValidW3cTraceIds() {
        assertThat(TraceIdFilter.parseTraceId("00-0123456789ABCDEF0123456789ABCDEF-0123456789abcdef-01"))
                .isEqualTo("0123456789abcdef0123456789abcdef");
        assertThat(TraceIdFilter.parseTraceId("invalid")).isNull();
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
        assertThat(TraceIdFilter.legacyTraceId()).matches("[0-9a-f]{16}");
        MDC.put("traceId", "abc");
        try {
            assertThat(TraceIdFilter.buildTraceparent()).matches("00-[0-9a-f]{32}-[0-9a-f]{16}-01");
        } finally {
            MDC.clear();
        }
    }
}
