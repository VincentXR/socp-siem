package com.socp.gateway.security;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Development-only revocation backend; production defaults to Redis. */
@Component
@ConditionalOnProperty(name = "socp.auth.revocation.backend", havingValue = "memory")
class InMemoryTokenRevocationStore implements TokenRevocationStore {

    private final Map<String, Instant> revoked = new ConcurrentHashMap<>();

    @Override
    public Mono<Void> revoke(String jti, Instant expiresAt) {
        if (jti != null && !jti.isBlank() && expiresAt != null && expiresAt.isAfter(Instant.now())) {
            revoked.put(jti, expiresAt);
        }
        cleanup();
        return Mono.empty();
    }

    @Override
    public Mono<Boolean> isRevoked(String jti) {
        cleanup();
        Instant until = jti == null ? null : revoked.get(jti);
        return Mono.just(until != null && until.isAfter(Instant.now()));
    }

    private void cleanup() {
        Instant now = Instant.now();
        revoked.entrySet().removeIf(entry -> !entry.getValue().isAfter(now));
    }
}
