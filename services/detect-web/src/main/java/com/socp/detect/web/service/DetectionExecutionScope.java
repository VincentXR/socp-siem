package com.socp.detect.web.service;

import com.socp.platform.obs.trace.TracePropagation;
import com.socp.platform.tenant.context.TenantContext;
import com.socp.rule.engine.RuleExecutionScope;
import com.socp.rule.model.SecurityEvent;
import io.opentelemetry.context.Context;
import org.slf4j.MDC;

/** Carries the submitting event's trace across the rule worker queue without
 * putting transport metadata into user-controlled business fields. */
final class DetectionExecutionScope implements RuleExecutionScope {
    @Override
    public RuleExecutionScope capture() {
        Context captured = Context.current();
        String legacyTrace = MDC.get("traceId");
        return event -> open(event, captured, legacyTrace);
    }

    @Override
    public Scope open(SecurityEvent event) {
        return open(event, Context.current(), MDC.get("traceId"));
    }

    private static Scope open(SecurityEvent event, Context captured, String legacyTrace) {
        var tenantScope = TenantContext.open(event.requireTenantId());
        var traceScope = captured.makeCurrent();
        String previous = MDC.get("traceId");
        String traceId = TracePropagation.traceId(captured);
        // Disabled SDKs still carry a validated log-correlation ID in MDC.
        if (traceId == null && legacyTrace != null && legacyTrace.matches("[0-9a-fA-F]{32}")
                && !legacyTrace.chars().allMatch(c -> c == '0')) {
            traceId = legacyTrace.toLowerCase(java.util.Locale.ROOT);
        }
        if (traceId == null) MDC.remove("traceId");
        else MDC.put("traceId", traceId);
        return () -> {
            try {
                if (previous == null) MDC.remove("traceId");
                else MDC.put("traceId", previous);
            } finally {
                try { traceScope.close(); }
                finally { tenantScope.close(); }
            }
        };
    }
}
