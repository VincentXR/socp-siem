package com.socp.platform.obs.web;
import com.socp.platform.obs.config.OTelSetup;
import com.socp.platform.obs.trace.TracePropagation;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.context.propagation.TextMapSetter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.regex.Pattern;

/**
 * 全链路 traceId（2026-08-10 OTel 完整版）：
 * <ul>
 *   <li>SDK 初始化成功：用 OpenTelemetry W3C trace context propagator 提取/注入
 *       {@code traceparent}，每个 HTTP 请求创建一个 Span（父子链路），trace-id 写入 MDC
 *       → Jaeger 可视化（localhost:4317 OTLP）。</li>
 *   <li>SDK 初始化失败（Jaeger 不可达）：回退手写 traceparent 解析/生成（协议兼容）。</li>
 * </ul>
 * traceId 由 socp-obs logback 打印，ApiResult 回填给前端；服务间 HTTP/Kafka 透传 traceparent（W3C）。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class TraceIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Trace-Id";
    public static final String TRACEPARENT = "traceparent";

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Pattern TRACEPARENT_PATTERN = Pattern.compile(
            "^(?![fF]{2})([0-9a-fA-F]{2})-([0-9a-fA-F]{32})-([0-9a-fA-F]{16})-([0-9a-fA-F]{2})$");
    private static final Pattern TRACE_ID_PATTERN = Pattern.compile("[0-9a-fA-F]{32}");

    private final org.springframework.core.env.Environment env;

    public TraceIdFilter(org.springframework.core.env.Environment env) {
        this.env = env;
    }

    /** 解析 W3C traceparent，返回 32-hex trace-id；格式非法返回 null。 */
    public static String parseTraceId(String traceparent) {
        if (traceparent == null) return null;
        var match = TRACEPARENT_PATTERN.matcher(traceparent.trim());
        if (!match.matches() || allZero(match.group(2)) || allZero(match.group(3))) return null;
        return match.group(2).toLowerCase(java.util.Locale.ROOT);
    }

    /** 生成新 span-id（16 hex）。 */
    public static String newSpanId() {
        byte[] b = new byte[8];
        String value;
        do {
            RANDOM.nextBytes(b);
            value = HexFormat.of().formatHex(b);
        } while (allZero(value));
        return value;
    }

    /** 生成新 trace-id（32 hex）。 */
    public static String newTraceId() {
        byte[] b = new byte[16];
        String value;
        do {
            RANDOM.nextBytes(b);
            value = HexFormat.of().formatHex(b);
        } while (allZero(value));
        return value;
    }

    /**
     * 出站 traceparent。优先渲染当前真实 Span（这样 downstream 的父 span 确实存在）；
     * 只有在没有 live span 时才回退 MDC + 新 span-id。
     *
     * <p>手写回退会造出一个没有任何 exporter 见过的 span-id，downstream 会把
     * 它当父 span 引用，于是 trace 里出现一个悬空的父引用：同一个 trace-id
     * 但不成树。所以只要能拿到 live span 就必须用 propagator 渲染。
     */
    public static String buildTraceparent() {
        String live = TracePropagation.currentTraceparent();
        if (live != null) {
            return live;
        }
        String tid = normalizeTraceId(MDC.get("traceId"));
        if (tid == null) return null;
        return "00-" + tid + "-" + newSpanId() + "-01";
    }

    /** Accepts only protocol-valid W3C trace identifiers. */
    private static String normalizeTraceId(String value) {
        if (value == null || !TRACE_ID_PATTERN.matcher(value).matches() || allZero(value)) return null;
        return value.toLowerCase(java.util.Locale.ROOT);
    }

    private static boolean allZero(String value) {
        return value.chars().allMatch(ch -> ch == '0');
    }

    // ---- OTel propagation ----
    private static final TextMapGetter<HttpServletRequest> GETTER = new TextMapGetter<>() {
        @Override
        public Iterable<String> keys(HttpServletRequest carrier) {
            return java.util.Collections.list(carrier.getHeaderNames());
        }

        @Override
        public String get(HttpServletRequest carrier, String key) {
            return carrier.getHeader(key);
        }
    };

    private static final TextMapSetter<HttpServletResponse> SETTER = (carrier, key, value) ->
            carrier.setHeader(key, value);

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String svc = env == null ? "" : env.getProperty("spring.application.name", "");
        boolean tracingEnabled = env != null && Boolean.parseBoolean(
                env.getProperty("socp.obs.tracing.enabled",
                        env.getProperty("SOCP_TRACING_ENABLED", "false")));
        OTelSetup.initIfNeeded(svc, tracingEnabled);
        if (OTelSetup.isInitialized()) {
            doWithOtel(req, res, chain);
        } else {
            doManual(req, res, chain);
        }
    }

    /** OTel SDK 路径：propagator 提取父上下文 → 创建 SERVER Span → MDC → 响应注入。 */
    private void doWithOtel(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws IOException, ServletException {
        Context parent = TracePropagation.extract(GETTER, req);
        String method = req.getMethod() == null ? "HTTP" : req.getMethod();
        String path = req.getRequestURI();
        Span span = TracePropagation.startSpan(method + " " + path, SpanKind.SERVER, parent);
        // Bound once and reused for injection: try-with-resources closes the
        // scope before the finally block runs, so Context.current() there no
        // longer carries this span. Injecting from Context.current() in the
        // finally block used to publish the caller's span instead of ours.
        Context spanContext = Context.current().with(span);
        String traceId = TracePropagation.traceId(spanContext);
        if (traceId == null) {
            traceId = newTraceId();
        }
        MDC.put("traceId", traceId);
        span.setAttribute("http.request.method", method);
        span.setAttribute("url.path", path);
        res.setHeader(HEADER, traceId);

        Throwable failure = null;
        try (Scope scope = spanContext.makeCurrent()) {
            chain.doFilter(req, res);
        } catch (Throwable t) {
            failure = t;
            throw t;
        } finally {
            if (failure == null && res.getStatus() >= 500) {
                span.setStatus(StatusCode.ERROR);
            }
            span.setAttribute("http.response.status_code", res.getStatus());
            // 响应注入 W3C traceparent（若未由其他组件写入）
            if (res.getHeader(TRACEPARENT) == null) {
                TracePropagation.inject(spanContext, SETTER, res);
            }
            TracePropagation.finish(span, failure);
            MDC.remove("traceId");
        }
    }

    /** 手写回退：解析/生成 traceparent（协议兼容 OTel W3C）。 */
    private void doManual(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws IOException, ServletException {
        String traceId = null;
        String traceparent = req.getHeader(TRACEPARENT);
        if (traceparent != null && !traceparent.isBlank()) {
            traceId = parseTraceId(traceparent);
        }
        if (traceId == null) {
            String legacy = req.getHeader(HEADER);
            traceId = normalizeTraceId(legacy);
        }
        if (traceId == null) {
            traceId = newTraceId();
        }
        MDC.put("traceId", traceId);
        res.setHeader(TRACEPARENT, "00-" + traceId + "-" + newSpanId() + "-01");
        res.setHeader(HEADER, traceId);
        try {
            chain.doFilter(req, res);
        } finally {
            MDC.remove("traceId");
        }
    }
}
