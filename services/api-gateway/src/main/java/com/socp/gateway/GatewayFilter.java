package com.socp.gateway;

import com.nimbusds.jwt.JWTClaimsSet;
import com.socp.platform.auth.JwtValidationException;
import com.socp.platform.auth.JwtValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

/**
 * 网关全局过滤器（WebFlux）：做鉴权 + 全链路 traceId + 租户透传。
 *  - 鉴权：北向必须带 Bearer；非 dev-bypass 模式下用 {@link JwtValidator} 验签 + 校验 exp/issuer，
 *    与各业务服务的 AuthInterceptor 共用同一份实现，避免网关放行、服务再拒绝的口径分裂。
 *  - traceId：透传 X-Trace-Id，缺失则生成，回写响应头，便于前端去 Jaeger 下钻（§0.3 / P18）。
 *  - 租户：优先取 JWT claim 里的租户，其次透传请求头 X-Tenant-Id（§3.3）。
 * 生产环境此处还应挂 Spring Cloud CircuitBreaker + RequestRateLimiter（Redis）。
 */
@Component
public class GatewayFilter implements GlobalFilter, Ordered {
    private static final Logger log = LoggerFactory.getLogger(GatewayFilter.class);
    private static final String BEARER = "Bearer ";

    private final JwtValidator jwtValidator;

    public GatewayFilter(JwtValidator jwtValidator) {
        this.jwtValidator = jwtValidator;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String incoming = exchange.getRequest().getHeaders().getFirst("X-Trace-Id");
        // 必须 effectively final 才能被下面的 lambda 捕获。
        // traceId 在鉴权之前生成：被拒绝的请求同样要可追溯（安全平台需要审计"谁在无令牌打接口"）。
        final String traceId = (incoming == null || incoming.isBlank())
                ? UUID.randomUUID().toString().replace("-", "").substring(0, 16)
                : incoming;

        // 响应头回写：ServerWebExchange.Builder#response 只接受 ServerHttpResponse 实例，
        // 不能传 lambda；WebFlux 里加响应头的正确姿势是 beforeCommit（响应提交前钩子）。
        exchange.getResponse().beforeCommit(() -> {
            exchange.getResponse().getHeaders().set("X-Trace-Id", traceId);
            return Mono.empty();
        });

        String auth = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        String path = exchange.getRequest().getPath().value();
        // 登录端点放行（签发令牌的入口本身不能要求令牌；OIDC 跳转/回调同此）
        if (path.startsWith("/auth/login") || path.startsWith("/auth/oidc/")) {
            ServerWebExchange mutated = exchange.mutate()
                    .request(r -> r.headers(headers -> headers.set("X-Trace-Id", traceId)))
                    .build();
            return chain.filter(mutated);
        }
        if (auth == null || !auth.startsWith(BEARER) || auth.substring(BEARER.length()).isBlank()) {
            log.warn("鉴权失败(缺少令牌) traceId={} path={} remote={}", traceId,
                    exchange.getRequest().getPath().value(), exchange.getRequest().getRemoteAddress());
            return reject(exchange, traceId, "缺少或无效的 Bearer 令牌");
        }

        String tenant = exchange.getRequest().getHeaders().getFirst("X-Tenant-Id");
        if (!jwtValidator.isDevBypass()) {
            JWTClaimsSet claims;
            try {
                claims = jwtValidator.validate(auth.substring(BEARER.length()));
            } catch (JwtValidationException e) {
                log.warn("鉴权失败(令牌无效) traceId={} path={} reason={}", traceId,
                        exchange.getRequest().getPath().value(), e.getMessage());
                return reject(exchange, traceId, e.getMessage());
            }
            String claimTenant = jwtValidator.extractTenant(claims);
            if (claimTenant != null && !claimTenant.isBlank() && !"default".equals(claimTenant)) {
                // JWT 里的租户是权威来源，覆盖客户端自报的请求头，防止越权跨租户读数据。
                // 例外：登录内置账号（demo/admin）签发的 token 固定 tenant=default——它是演示/机机账号，
                // 租户以请求头 X-Tenant-Id 为准（verify-slice 多租户测试即用此约定）。
                tenant = claimTenant;
            }
            // RBAC：viewer 角色只读，写操作（POST/PUT/DELETE）拒绝
            try {
                String role = claims.getStringClaim("role");
                String method = exchange.getRequest().getMethod() == null ? "GET" : exchange.getRequest().getMethod().name();
                if ("viewer".equals(role) && !("GET".equals(method) || "OPTIONS".equals(method))) {
                    log.warn("RBAC 拒绝(viewer 只读) traceId={} path={} method={}", traceId,
                            exchange.getRequest().getPath().value(), method);
                    return rejectForbidden(exchange, traceId, "viewer 角色为只读，无写权限");
                }
            } catch (java.text.ParseException ignored) {
                // 无 role claim 的令牌（旧版）按无限制处理
            }
        }

        final String resolvedTenant = tenant;
        ServerWebExchange mutated = exchange.mutate()
                .request(r -> r.headers(headers -> {
                    headers.set("X-Trace-Id", traceId);
                    if (resolvedTenant != null && !resolvedTenant.isBlank()) {
                        headers.set("X-Tenant-Id", resolvedTenant);
                    }
                }))
                .build();

        return chain.filter(mutated);
    }

    /**
     * 网关侧拒绝：返回与后端 ApiResult 完全一致的统一响应体，
     * 否则前端在"网关拦截"和"服务拦截"两种场景下要写两套解析逻辑。
     * 这里手写 JSON 而非依赖 ApiResult：网关是 WebFlux，MDC 在响应式线程上不可靠。
     */
    private Mono<Void> reject(ServerWebExchange exchange, String traceId, String message) {
        return rejectWith(exchange, traceId, message, HttpStatus.UNAUTHORIZED);
    }

    private Mono<Void> rejectForbidden(ServerWebExchange exchange, String traceId, String message) {
        return rejectWith(exchange, traceId, message, HttpStatus.FORBIDDEN);
    }

    private Mono<Void> rejectWith(ServerWebExchange exchange, String traceId, String message, HttpStatus status) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        String body = "{\"code\":" + status.value() + ",\"message\":\"" + escapeJson(message) + "\",\"traceId\":\""
                + traceId + "\",\"timestamp\":\"" + Instant.now() + "\"}";
        DataBuffer buffer = response.bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8));
        return response.writeWith(Mono.just(buffer));
    }

    /** 校验失败原因来自 nimbus 异常文本，可能带引号/反斜杠，不转义会拼出非法 JSON */
    private static String escapeJson(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }

    @Override
    public int getOrder() {
        return -1;
    }
}
