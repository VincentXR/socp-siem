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

/** Redis-backed deny-list so logout is effective across gateway replicas. */
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
