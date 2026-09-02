package com.socp.gateway.filter;

import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.Set;

/**
 * Applies the gateway authentication contract to local endpoints handled by
 * WebFlux controllers instead of gateway routes. GlobalFilter instances only
 * run for matched gateway routes.
 */
@Component
public class AuthSessionWebFilter implements WebFilter, Ordered {

    private static final Set<String> LOCAL_AUTHENTICATED_PATHS = Set.of(
            "/auth/session", "/api/v1/system/health");

    private final GatewayFilter gatewayFilter;

    public AuthSessionWebFilter(GatewayFilter gatewayFilter) {
        this.gatewayFilter = gatewayFilter;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (!LOCAL_AUTHENTICATED_PATHS.contains(exchange.getRequest().getPath().value())) {
            return chain.filter(exchange);
        }
        return gatewayFilter.filter(exchange, chain::filter);
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 10;
    }
}
