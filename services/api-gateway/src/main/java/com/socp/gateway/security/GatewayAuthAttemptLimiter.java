package com.socp.gateway.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/** Redis-backed auth limiter with a bounded development fallback. */
@Component
public class GatewayAuthAttemptLimiter implements AuthAttemptLimiter {

    private static final Logger log = LoggerFactory.getLogger(GatewayAuthAttemptLimiter.class);
    private static final DefaultRedisScript<Long> INCREMENT = new DefaultRedisScript<>("""
            local current = redis.call('INCR', KEYS[1])
            if current == 1 then redis.call('EXPIRE', KEYS[1], ARGV[1]) end
            return current
            """, Long.class);
    private static final String PREFIX = "socp:gateway:auth-attempt:";

    private final ReactiveStringRedisTemplate redis;
    private final String backend;
    private final boolean failClosed;
    private final int loginPermits;
    private final int servicePermits;
    private final int windowSeconds;
    @Value("${socp.ratelimit.local-max-entries:10000}")
    private int localMaxEntries = 10_000;
    private final ConcurrentHashMap<String, Window> local = new ConcurrentHashMap<>();

    public GatewayAuthAttemptLimiter(
            ReactiveStringRedisTemplate redis,
            @Value("${socp.ratelimit.backend:memory}") String backend,
            @Value("${socp.ratelimit.fail-closed:false}") boolean failClosed,
            @Value("${socp.auth.rate-limit.login-permits:5}") int loginPermits,
            @Value("${socp.auth.rate-limit.service-permits:10}") int servicePermits,
            @Value("${socp.auth.rate-limit.window-seconds:60}") int windowSeconds) {
        this.redis = redis;
        this.backend = backend;
        this.failClosed = failClosed;
        this.loginPermits = Math.max(1, loginPermits);
        this.servicePermits = Math.max(1, servicePermits);
        this.windowSeconds = Math.max(1, windowSeconds);
    }

    @Override
    public Mono<Decision> acquire(String kind, String clientAddress, String identity) {
        String key = key(kind, clientAddress, identity);
        int permits = "service".equals(kind) ? servicePermits : loginPermits;
        if (!"redis".equalsIgnoreCase(backend)) {
            return Mono.just(localAcquire(key, permits));
        }
        return redis.execute(INCREMENT, List.of(key), List.of(String.valueOf(windowSeconds)))
                .next()
                .map(count -> count <= permits ? Decision.permit() : Decision.reject(windowSeconds))
                .switchIfEmpty(Mono.defer(() -> onRedisFailure(key, permits, "empty Redis response")))
                .onErrorResume(failure -> onRedisFailure(key, permits, failure.getMessage()));
    }

    @Override
    public Mono<Void> reset(String kind, String clientAddress, String identity) {
        String key = key(kind, clientAddress, identity);
        local.remove(key);
        if (!"redis".equalsIgnoreCase(backend)) return Mono.empty();
        return redis.delete(key).then().onErrorResume(failure -> {
            log.warn("Unable to reset auth rate-limit key: {}", failure.getMessage());
            return Mono.empty();
        });
    }

    private Mono<Decision> onRedisFailure(String key, int permits, String reason) {
        if (failClosed) {
            log.error("Redis auth limiter unavailable; rejecting because fail-closed is enabled: {}", reason);
            return Mono.just(Decision.reject(1));
        }
        log.warn("Redis auth limiter unavailable; using local fallback: {}", reason);
        return Mono.just(localAcquire(key, permits));
    }

    private Decision localAcquire(String key, int permits) {
        long now = System.nanoTime();
        long window = Duration.ofSeconds(windowSeconds).toNanos();
        Window result = local.compute(key, (ignored, current) -> {
            if (current == null || now >= current.expiresAtNanos) {
                return new Window(1, now + window);
            }
            return new Window(current.count + 1, current.expiresAtNanos);
        });
        cleanupLocal(now);
        long retry = Math.max(1, Duration.ofNanos(Math.max(0, result.expiresAtNanos - now)).toSeconds());
        return result.count <= permits ? Decision.permit() : Decision.reject(retry);
    }

    private void cleanupLocal(long now) {
        int limit = Math.max(1, Math.min(1_000_000, localMaxEntries));
        if (local.size() <= limit) return;
        local.entrySet().removeIf(entry -> now >= entry.getValue().expiresAtNanos);
        int excess = local.size() - limit;
        if (excess <= 0) return;
        local.entrySet().stream()
                .sorted(Comparator.comparingLong(entry -> entry.getValue().expiresAtNanos))
                .limit(excess)
                .forEach(entry -> local.remove(entry.getKey(), entry.getValue()));
    }

    private static String key(String kind, String address, String identity) {
        String material = normalized(address) + '\u0000' + normalized(identity);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(material.getBytes(StandardCharsets.UTF_8));
            return PREFIX + normalized(kind) + ':' + HexFormat.of().formatHex(digest);
        } catch (Exception impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private static String normalized(String value) {
        return value == null || value.isBlank() ? "unknown" : value.trim().toLowerCase();
    }

    private record Window(int count, long expiresAtNanos) {
    }
}
