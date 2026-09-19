package com.socp.rule.state;

import java.util.Comparator;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

/**
 * Bounded, idle-expiring state map for high-cardinality rule keys.
 *
 * <p>Cleanup is amortized so event processing does not scan the whole map on
 * every event.  Capacity enforcement is amortized the same way: one bounded
 * pass removes a batch of least-recently-used keys, and the following
 * insertions stay under the limit without another scan.  Eviction is best
 * effort: a concurrent event may recreate a key after it has been selected for
 * eviction, which is safe because rule state is only an optimization window and
 * durable detection state remains authoritative.
 */
public final class RuleStateMap<V> {

    private static final int CLEANUP_MASK = 1023;
    /** One capacity pass frees this fraction of the bound, then earns credit. */
    private static final int EVICTION_BATCH_DIVISOR = 10;

    private final ConcurrentHashMap<String, Entry<V>> entries = new ConcurrentHashMap<>();
    private final int maxKeys;
    private final int evictionBatch;
    private final long idleNanos;
    private final AtomicLong operations = new AtomicLong();
    private final AtomicLong evictions = new AtomicLong();
    private final AtomicLong capacityPasses = new AtomicLong();

    public RuleStateMap() {
        this(RuleStateLimits.defaults());
    }

    public RuleStateMap(RuleStateLimits limits) {
        Objects.requireNonNull(limits, "limits");
        this.maxKeys = limits.maxKeys();
        this.evictionBatch = Math.max(1, this.maxKeys / EVICTION_BATCH_DIVISOR);
        this.idleNanos = limits.idleTtl().toNanos();
    }

    public V get(String key, Supplier<V> factory) {
        if (key == null || key.isBlank()) return null;
        long now = System.nanoTime();
        Entry<V> entry = entries.computeIfAbsent(key, ignored -> new Entry<>(factory.get(), now));
        entry.lastAccessNanos = now;
        long op = operations.incrementAndGet();
        if ((op & CLEANUP_MASK) == 0) cleanup(now);
        if (entries.size() > maxKeys) evictOldest(evictionBatch);
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

    /** Number of full scans spent on capacity enforcement; the amortization proof. */
    public long evictionPasses() {
        return capacityPasses.get();
    }

    /** Remove every state key, used when restoring a complete snapshot. */
    public void clear() {
        entries.clear();
    }

    public Map<String, Object> stats() {
        return Map.of("stateKeys", size(), "stateMaxKeys", maxKeys,
                "stateIdleTtlMs", idleNanos / 1_000_000L, "stateEvictions", evictions(),
                "stateEvictionPasses", evictionPasses());
    }

    private void cleanup(long now) {
        long cutoff = now - idleNanos;
        entries.forEach((key, entry) -> {
            if (entry.lastAccessNanos < cutoff && entries.remove(key, entry)) evictions.incrementAndGet();
        });
    }

    /**
     * One bounded pass over the map selects the {@code count} least recently
     * accessed keys. Removal therefore costs one scan per {@code count}
     * insertions instead of one scan per insertion.
     */
    private void evictOldest(int count) {
        capacityPasses.incrementAndGet();
        Comparator<Map.Entry<String, Entry<V>>> byAccess =
                Comparator.comparingLong(candidate -> candidate.getValue().lastAccessNanos);
        PriorityQueue<Map.Entry<String, Entry<V>>> oldest =
                new PriorityQueue<>(count, byAccess.reversed());
        for (Map.Entry<String, Entry<V>> candidate : entries.entrySet()) {
            if (oldest.size() < count) {
                oldest.add(candidate);
            } else if (byAccess.compare(candidate, oldest.peek()) < 0) {
                oldest.poll();
                oldest.add(candidate);
            }
        }
        for (Map.Entry<String, Entry<V>> candidate : oldest) {
            if (entries.remove(candidate.getKey(), candidate.getValue())) evictions.incrementAndGet();
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
