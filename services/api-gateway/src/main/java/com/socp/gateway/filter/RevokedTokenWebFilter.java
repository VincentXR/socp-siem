package com.socp.gateway.filter;

import com.nimbusds.jwt.SignedJWT;
import com.socp.gateway.api.controller.AuthController;
import com.socp.gateway.security.TokenRevocationStore;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;

/** Rejects sessions revoked on any gateway replica before routing or local controller dispatch. */
@Component
public class RevokedTokenWebFilter implements WebFilter, Ordered {

    private static final String BEARER = "Bearer ";
    private final TokenRevocationStore revocations;

    public RevokedTokenWebFilter(TokenRevocationStore revocations) {
        this.revocations = revocations;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String token = token(exchange);
        if (token == null) return chain.filter(exchange);

        String jti = jti(token);
        if (jti == null) return chain.filter(exchange);

        return revocations.isRevoked(jti)
                .flatMap(revoked -> revoked
                        ? reject(exchange, HttpStatus.UNAUTHORIZED, "Session has been revoked")
                        : chain.filter(exchange))
                .onErrorResume(failure -> reject(exchange, HttpStatus.SERVICE_UNAVAILABLE,
                        "Session revocation service unavailable"));
    }

    private static String token(ServerWebExchange exchange) {
        String auth = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (auth != null && auth.startsWith(BEARER) && !auth.substring(BEARER.length()).isBlank()) {
            return auth.substring(BEARER.length()).trim();
        }
        var cookie = exchange.getRequest().getCookies().getFirst(AuthController.SESSION_COOKIE);
        return cookie == null || cookie.getValue().isBlank() ? null : cookie.getValue();
    }

    private static String jti(String token) {
        try {
            return SignedJWT.parse(token).getJWTClaimsSet().getJWTID();
        } catch (Exception malformed) {
            // Signature/format validation remains the responsibility of GatewayFilter.
            return null;
        }
    }

    private static Mono<Void> reject(ServerWebExchange exchange, HttpStatus status, String message) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        String body = "{\"code\":" + status.value() + ",\"message\":\"" + message + "\"}";
        DataBuffer buffer = response.bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8));
        return response.writeWith(Mono.just(buffer));
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 5;
    }
}
