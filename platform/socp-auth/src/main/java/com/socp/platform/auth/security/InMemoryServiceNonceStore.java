package com.socp.platform.auth.security;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** Development fallback. Production uses the Redis implementation from socp-ratelimit. */
@Component
@ConditionalOnProperty(name = "socp.ratelimit.backend", havingValue = "memory", matchIfMissing = true)
public class InMemoryServiceNonceStore implements ServiceNonceStore {

    private final ConcurrentHashMap<String, Long> nonces = new ConcurrentHashMap<>();
    private final AtomicLong operations = new AtomicLong();

    @Override
    public ClaimResult claim(String service, String nonce, Duration ttl) {
        long now = Instant.now().getEpochSecond();
        long expiresAt = now + Math.max(1, ttl.toSeconds());
        if ((operations.incrementAndGet() & 255) == 0) {
            nonces.entrySet().removeIf(entry -> entry.getValue() <= now);
        }
        String key = service + ':' + nonce;
        Long existing = nonces.putIfAbsent(key, expiresAt);
        if (existing == null) return ClaimResult.CLAIMED;
        if (existing <= now && nonces.replace(key, existing, expiresAt)) return ClaimResult.CLAIMED;
        return ClaimResult.REPLAYED;
    }

    int size() {
        return nonces.size();
    }
}
