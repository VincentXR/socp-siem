package com.socp.gateway.filter;

import com.socp.platform.tenant.security.ServiceRequestSignature;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.filter.RouteToRequestUrlFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.UUID;

/** Creates a one-use identity proof for each actual upstream routing attempt. */
@Component
public final class GatewayRequestSigningFilter implements GlobalFilter, Ordered {
    private final String serviceSecret;

    public GatewayRequestSigningFilter(@Value("${socp.security.service-secret:}") String serviceSecret) {
        this.serviceSecret = serviceSecret == null ? "" : serviceSecret.trim();
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        return Mono.defer(() -> {
            String path = exchange.getAttribute(GatewayFilter.TRUSTED_GATEWAY_PATH);
            if (path == null || serviceSecret.isBlank()) return chain.filter(exchange);
            String token = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
            String tenant = exchange.getRequest().getHeaders().getFirst("X-Tenant-Id");
            String method = exchange.getRequest().getMethod().name();
            String timestamp = String.valueOf(Instant.now().getEpochSecond());
            String nonce = UUID.randomUUID().toString();
            String signature = ServiceRequestSignature.sign(serviceSecret,
                    ServiceRequestSignature.GATEWAY_SERVICE, method,
                    ServiceRequestSignature.gatewayBinding(path, token.substring("Bearer ".length()).trim()),
                    tenant, timestamp, nonce);
            var signed = exchange.mutate().request(request -> request.headers(headers -> {
                headers.set(ServiceRequestSignature.GATEWAY_PATH_HEADER, path);
                headers.set(ServiceRequestSignature.GATEWAY_TIMESTAMP_HEADER, timestamp);
                headers.set(ServiceRequestSignature.GATEWAY_NONCE_HEADER, nonce);
                headers.set(ServiceRequestSignature.GATEWAY_SIGNATURE_HEADER, signature);
            })).build();
            return chain.filter(signed);
        });
    }

    @Override
    public int getOrder() {
        // Route retries wrap this filter; URL/path rewrites have already run.
        return RouteToRequestUrlFilter.ROUTE_TO_URL_FILTER_ORDER + 1;
    }
}
