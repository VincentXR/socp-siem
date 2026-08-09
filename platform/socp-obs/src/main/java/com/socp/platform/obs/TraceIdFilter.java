package com.socp.platform.obs;

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
import java.util.UUID;

/**
 * 全链路 traceId（W3C traceparent 标准，2026-08-10 升级）：
 * <ul>
 *   <li>入站：优先解析 W3C {@code traceparent} 头（{@code 00-<32hex trace-id>-<16hex span-id>-<flags>}），
 *       trace-id 写入 MDC("traceId")；兼容旧 {@code X-Trace-Id} 头；缺失则生成 32 hex trace-id。</li>
 *   <li>出站：响应回写 {@code traceparent}（trace-id 沿用、span-id 为本请求新 span）+ 兼容 {@code X-Trace-Id}。</li>
 * </ul>
 * traceId 由 socp-obs logback 打印，ApiResult 回填给前端；服务间 HTTP/Kafka 透传见各调用方。
 * 生产环境可替换为 OpenTelemetry SDK 的 propagation（本实现保持轻量可运行、协议兼容）。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class TraceIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Trace-Id";
    public static final String TRACEPARENT = "traceparent";

    private static final SecureRandom RANDOM = new SecureRandom();

    /** 解析 W3C traceparent，返回 32-hex trace-id；格式非法返回 null。 */
    public static String parseTraceId(String traceparent) {
        if (traceparent == null) return null;
        String[] parts = traceparent.trim().split("-");
        if (parts.length < 2) return null;
        String tid = parts[1];
        if (tid.length() == 32 && tid.matches("[0-9a-fA-F]{32}")) {
            return tid.toLowerCase();
        }
        return null;
    }

    /** 生成新 span-id（16 hex）。 */
    public static String newSpanId() {
        byte[] b = new byte[8];
        RANDOM.nextBytes(b);
        return HexFormat.of().formatHex(b);
    }

    /** 生成新 trace-id（32 hex）。 */
    public static String newTraceId() {
        byte[] b = new byte[16];
        RANDOM.nextBytes(b);
        return HexFormat.of().formatHex(b);
    }

    /** 从 MDC 构建出站 traceparent（沿用当前 traceId + 新 span-id）；无 traceId 返回 null。 */
    public static String buildTraceparent() {
        String traceId = MDC.get("traceId");
        if (traceId == null || traceId.isBlank()) return null;
        String tid = traceId.length() == 32 ? traceId : String.format("%0" + (32 - traceId.length()) + "d", 0) + traceId;
        return "00-" + tid + "-" + newSpanId() + "-01";
    }

    /** 旧 UUID/16hex 生成逻辑兼容。 */
    public static String legacyTraceId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String traceId = null;
        String traceparent = req.getHeader(TRACEPARENT);
        if (traceparent != null && !traceparent.isBlank()) {
            traceId = parseTraceId(traceparent);
        }
        if (traceId == null) {
            String legacy = req.getHeader(HEADER);
            if (legacy != null && !legacy.isBlank()) {
                traceId = legacy;
            }
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
