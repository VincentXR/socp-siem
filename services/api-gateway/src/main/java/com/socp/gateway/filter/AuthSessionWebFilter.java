package com.socp.gateway.filter;

import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Applies the gateway authentication contract to local authentication
 * endpoints that are handled by WebFlux controllers instead of gateway
 * routes. GlobalFilter instances only run for matched gateway routes.
 */
@Component
public class AuthSessionWebFilter implements WebFilter, Ordered {

    private static final String SESSION_PATH = "/auth/session";

    private final GatewayFilter gatewayFilter;

    public AuthSessionWebFilter(GatewayFilter gatewayFilter) {
        this.gatewayFilter = gatewayFilter;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (!SESSION_PATH.equals(exchange.getRequest().getPath().value())) {
            return chain.filter(exchange);
        }
        return gatewayFilter.filter(exchange, chain::filter);
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 10;
    }
}
