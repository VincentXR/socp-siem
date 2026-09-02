package com.socp.platform.ratelimit.store;
import com.socp.platform.ratelimit.model.TokenBucket;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Component
@ConditionalOnProperty(name = "socp.ratelimit.backend", havingValue = "memory", matchIfMissing = true)
public class InMemoryRateLimitStore implements RateLimitStore {

    private static final long IDLE_NANOS = 30L * 60L * 1_000_000_000L;
    private static final int MAX_ENTRIES = 100_000;
    private final ConcurrentHashMap<String, Entry> buckets = new ConcurrentHashMap<>();
    private final AtomicLong operations = new AtomicLong();
    private final int maxEntries;

    public InMemoryRateLimitStore() {
        this(MAX_ENTRIES);
    }

    InMemoryRateLimitStore(int maxEntries) {
        this.maxEntries = Math.max(1, Math.min(MAX_ENTRIES, maxEntries));
    }

    @Override
    public Decision acquire(String key, int permits, int seconds) {
        long now = System.nanoTime();
        Entry entry = buckets.compute(key, (ignored, current) -> {
            if (current == null || current.permits != permits || current.seconds != seconds) {
                return new Entry(new TokenBucket(permits, seconds), permits, seconds, now);
            }
            current.lastUsed = now;
            return current;
        });
        if ((operations.incrementAndGet() & 1023) == 0) {
            buckets.entrySet().removeIf(item -> now - item.getValue().lastUsed > IDLE_NANOS);
            trimToLimit();
        }
        return entry.bucket.tryAcquire()
                ? Decision.permit() : Decision.rejected(entry.bucket.retryAfterSeconds());
    }

    int size() {
        return buckets.size();
    }

    private void trimToLimit() {
        int excess = buckets.size() - maxEntries;
        if (excess <= 0) return;
        buckets.entrySet().stream()
                .sorted(Comparator.comparingLong(item -> item.getValue().lastUsed))
                .limit(excess)
                .forEach(item -> buckets.remove(item.getKey(), item.getValue()));
    }

    private static final class Entry {
        private final TokenBucket bucket;
        private final int permits;
        private final int seconds;
        private volatile long lastUsed;

        private Entry(TokenBucket bucket, int permits, int seconds, long lastUsed) {
            this.bucket = bucket;
            this.permits = permits;
            this.seconds = seconds;
            this.lastUsed = lastUsed;
        }
    }
}
