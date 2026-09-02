package com.socp.platform.auth.security;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** Development fallback. Production uses the Redis implementation from socp-ratelimit. */
@Component
@ConditionalOnProperty(name = "socp.ratelimit.backend", havingValue = "memory", matchIfMissing = true)
public class InMemoryServiceNonceStore implements ServiceNonceStore {

    private static final int MAX_ENTRIES = 100_000;
    private final ConcurrentHashMap<String, Long> nonces = new ConcurrentHashMap<>();
    private final AtomicLong operations = new AtomicLong();

    @Override
    public ClaimResult claim(String service, String nonce, Duration ttl) {
        long now = Instant.now().getEpochSecond();
        long expiresAt = now + Math.max(1, ttl.toSeconds());
        if ((operations.incrementAndGet() & 255) == 0) {
            nonces.entrySet().removeIf(entry -> entry.getValue() <= now);
            trimToLimit();
        }
        String key = service + ':' + nonce;
        Long existing = nonces.putIfAbsent(key, expiresAt);
        if (existing == null) return ClaimResult.CLAIMED;
        if (existing <= now && nonces.replace(key, existing, expiresAt)) return ClaimResult.CLAIMED;
        return ClaimResult.REPLAYED;
    }

    private void trimToLimit() {
        int excess = nonces.size() - MAX_ENTRIES;
        if (excess <= 0) return;
        nonces.entrySet().stream()
                .sorted(Comparator.comparingLong(java.util.Map.Entry::getValue))
                .limit(excess)
                .forEach(entry -> nonces.remove(entry.getKey(), entry.getValue()));
    }

    int size() {
        return nonces.size();
    }
}
