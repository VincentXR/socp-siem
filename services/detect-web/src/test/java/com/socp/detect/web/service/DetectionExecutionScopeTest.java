package com.socp.detect.web.service;

import com.socp.platform.obs.trace.TracePropagation;
import com.socp.platform.tenant.context.TenantContext;
import com.socp.rule.engine.RuleEngine;
import com.socp.rule.engine.RuleProcessingObserver;
import com.socp.rule.model.SecurityEvent;
import com.socp.rule.rules.Rule;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.ContextPropagators;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DetectionExecutionScopeTest {
    @Test
    void queuePreservesEachCallersTraceIncludingAnUntracedNextEvent() throws Exception {
        GlobalOpenTelemetry.resetForTest();
        GlobalOpenTelemetry.set(OpenTelemetry.propagating(
                ContextPropagators.create(W3CTraceContextPropagator.getInstance())));
        var observed = new ConcurrentHashMap<String, String>();
        Rule rule = mock(Rule.class);
        when(rule.id()).thenReturn("scope-rule");
        when(rule.drain()).thenReturn(List.of());
        doAnswer(call -> {
            SecurityEvent event = call.getArgument(0);
            assertEquals("tenant-a", TenantContext.get());
            String trace = Span.current().getSpanContext().getTraceId();
            observed.put(event.host(), trace);
            String expectedMdc = event.host().equals("manual-correlation") ? "3".repeat(32)
                    : trace.equals("0".repeat(32)) ? null : trace;
            assertEquals(expectedMdc, MDC.get("traceId"));
            return null;
        }).when(rule).accept(any());
        try (var engine = new RuleEngine(List.of(rule), List.of(), null,
                RuleProcessingObserver.NOOP, new DetectionExecutionScope())) {
            engine.start();
            for (int i = 1; i <= 2; i++) {
                String trace = Integer.toString(i).repeat(32);
                try (var ignored = TracePropagation.contextFrom("00-" + trace + "-0123456789abcdef-01").makeCurrent()) {
                    engine.ingestAndAwait(event("traced-" + i)).get(3, TimeUnit.SECONDS);
                    assertEquals(trace, Span.current().getSpanContext().getTraceId());
                }
            }
            try (var ignored = Context.root().makeCurrent()) {
                try {
                    MDC.put("traceId", "3".repeat(32));
                    engine.ingestAndAwait(event("manual-correlation")).get(3, TimeUnit.SECONDS);
                    assertEquals("3".repeat(32), MDC.get("traceId"));
                } finally {
                    MDC.remove("traceId");
                }
                engine.ingestAndAwait(event("untraced")).get(3, TimeUnit.SECONDS);
                assertFalse(Span.current().getSpanContext().isValid());
            }
            assertEquals("1".repeat(32), observed.get("traced-1"));
            assertEquals("2".repeat(32), observed.get("traced-2"));
            assertEquals("0".repeat(32), observed.get("untraced"));
        } finally {
            GlobalOpenTelemetry.resetForTest();
        }
    }

    private static SecurityEvent event(String host) {
        return new SecurityEvent(Instant.now(), "scope-test", host, "controlled event",
                Map.of("tenant_id", "tenant-a"), com.socp.rule.model.Severity.INFO);
    }
}
