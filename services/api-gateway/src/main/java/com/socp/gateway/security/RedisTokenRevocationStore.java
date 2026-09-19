package com.socp.gateway.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;

/**
 * Redis-backed deny-list so logout is effective across gateway replicas.
 *
 * <p>Read path: {@link com.socp.gateway.filter.RevokedTokenWebFilter} fails closed
 * (503) when Redis cannot be reached. Reachability is not the whole contract —
 * a revoked key must survive until the session's own expiry. A key-policy that
 * can evict live keys (any {@code allkeys-*}, or {@code volatile-*} on a
 * memory-pressure instance) silently un-revokes sessions instead of erroring,
 * so the instance carrying {@code socp:auth:revoked:*} must be sized with
 * {@code maxmemory-policy noeviction} and separate from bulk cache traffic.
 * Key-prefix tiering does not protect here: eviction policies ignore prefixes.
 * That retention requirement belongs to the deployment baseline (Redis is not
 * delivered by the application chart).</p>
 */
@Component
@ConditionalOnProperty(name = "socp.auth.revocation.backend", havingValue = "redis", matchIfMissing = true)
class RedisTokenRevocationStore implements TokenRevocationStore {

    private final ReactiveStringRedisTemplate redis;

    @Value("${socp.auth.revocation.redis-key-prefix:socp:auth:revoked:}")
    private String keyPrefix;

    RedisTokenRevocationStore(ReactiveStringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public Mono<Void> revoke(String jti, Instant expiresAt) {
        if (jti == null || jti.isBlank() || expiresAt == null) return Mono.empty();
        Duration ttl = Duration.between(Instant.now(), expiresAt);
        if (ttl.isZero() || ttl.isNegative()) return Mono.empty();
        return redis.opsForValue().set(key(jti), "1", ttl).then();
    }

    @Override
    public Mono<Boolean> isRevoked(String jti) {
        if (jti == null || jti.isBlank()) return Mono.just(false);
        return redis.hasKey(key(jti)).defaultIfEmpty(false);
    }

    private String key(String jti) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(jti.getBytes(StandardCharsets.UTF_8));
            return keyPrefix + HexFormat.of().formatHex(digest);
        } catch (Exception failure) {
            throw new IllegalStateException("Unable to hash token identifier", failure);
        }
    }
}
