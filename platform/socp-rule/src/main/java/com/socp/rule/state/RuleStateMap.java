package com.socp.rule.state;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

/**
 * Bounded, idle-expiring state map for high-cardinality rule keys.
 *
 * <p>Cleanup is amortized so event processing does not scan the whole map on
 * every event.  Eviction is best effort: a concurrent event may recreate a key
 * after it has been selected for eviction, which is safe because rule state is
 * only an optimization window and durable detection state remains authoritative.
 */
public final class RuleStateMap<V> {

    private static final int CLEANUP_MASK = 1023;

    private final ConcurrentHashMap<String, Entry<V>> entries = new ConcurrentHashMap<>();
    private final int maxKeys;
    private final long idleNanos;
    private final AtomicLong operations = new AtomicLong();
    private final AtomicLong evictions = new AtomicLong();

    public RuleStateMap() {
        this(RuleStateLimits.defaults());
    }

    public RuleStateMap(RuleStateLimits limits) {
        Objects.requireNonNull(limits, "limits");
        this.maxKeys = limits.maxKeys();
        this.idleNanos = limits.idleTtl().toNanos();
    }

    public V get(String key, Supplier<V> factory) {
        if (key == null || key.isBlank()) return null;
        long now = System.nanoTime();
        Entry<V> entry = entries.computeIfAbsent(key, ignored -> new Entry<>(factory.get(), now));
        entry.lastAccessNanos = now;
        long op = operations.incrementAndGet();
        if ((op & CLEANUP_MASK) == 0) cleanup(now);
        if (entries.size() > maxKeys) evictOldest(now);
        return entry.value;
    }

    public void forEach(BiConsumer<String, V> consumer) {
        cleanup(System.nanoTime());
        entries.forEach((key, entry) -> consumer.accept(key, entry.value));
    }

    public int size() {
        cleanup(System.nanoTime());
        return entries.size();
    }

    public long evictions() {
        return evictions.get();
    }

    public Map<String, Object> stats() {
        return Map.of("stateKeys", size(), "stateMaxKeys", maxKeys,
                "stateIdleTtlMs", idleNanos / 1_000_000L, "stateEvictions", evictions());
    }

    private void cleanup(long now) {
        long cutoff = now - idleNanos;
        entries.forEach((key, entry) -> {
            if (entry.lastAccessNanos < cutoff && entries.remove(key, entry)) evictions.incrementAndGet();
        });
    }

    private void evictOldest(long now) {
        cleanup(now);
        while (entries.size() > maxKeys) {
            String oldestKey = null;
            Entry<V> oldest = null;
            for (Map.Entry<String, Entry<V>> candidate : entries.entrySet()) {
                if (oldest == null || candidate.getValue().lastAccessNanos < oldest.lastAccessNanos) {
                    oldestKey = candidate.getKey();
                    oldest = candidate.getValue();
                }
            }
            if (oldestKey == null || !entries.remove(oldestKey, oldest)) break;
            evictions.incrementAndGet();
        }
    }

    private static final class Entry<V> {
        private final V value;
        private volatile long lastAccessNanos;

        private Entry(V value, long lastAccessNanos) {
            this.value = value;
            this.lastAccessNanos = lastAccessNanos;
        }
    }
}
