package com.socp.gateway.filter;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/** Applies browser hardening headers to gateway responses, including errors. */
@Component
public class SecurityHeadersFilter implements GlobalFilter, Ordered {

    static final String CONTENT_SECURITY_POLICY = "default-src 'self'; object-src 'none'; "
            + "base-uri 'self'; frame-ancestors 'none'; form-action 'self'";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        exchange.getResponse().beforeCommit(() -> {
            HttpHeaders headers = exchange.getResponse().getHeaders();
            headers.set("Content-Security-Policy", CONTENT_SECURITY_POLICY);
            headers.set("X-Content-Type-Options", "nosniff");
            headers.set("X-Frame-Options", "DENY");
            headers.set("Referrer-Policy", "no-referrer");
            headers.set("Permissions-Policy", "camera=(), microphone=(), geolocation=()");
            if (isHttps(exchange)) {
                headers.set("Strict-Transport-Security", "max-age=31536000; includeSubDomains");
            }
            return Mono.empty();
        });
        return chain.filter(exchange);
    }

    private static boolean isHttps(ServerWebExchange exchange) {
        String forwarded = exchange.getRequest().getHeaders().getFirst("X-Forwarded-Proto");
        return "https".equalsIgnoreCase(forwarded)
                || "https".equalsIgnoreCase(exchange.getRequest().getURI().getScheme());
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
