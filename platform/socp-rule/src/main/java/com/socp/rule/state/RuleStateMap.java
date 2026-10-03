package com.socp.rule.state;

import java.io.ByteArrayOutputStream;
import java.util.Comparator;
import java.util.function.Function;
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
    private final Object cacheLock = new Object();
    private final long cacheLimit = Math.max(0, Math.min(64L * 1024 * 1024,
            Long.getLong("socp.rule.snapshot-cache.max-bytes", 8L * 1024 * 1024)));
    private long cacheBytes;
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
        synchronized (cacheLock) { entries.clear(); cacheBytes = 0; }
    }

    public Map<String, Object> stats() {
        return Map.of("stateKeys", size(), "stateMaxKeys", maxKeys,
                "stateIdleTtlMs", idleNanos / 1_000_000L, "stateEvictions", evictions(),
                "stateEvictionPasses", evictionPasses(), "stateSnapshotCacheBytes", cachedBytes(),
                "stateSnapshotCacheMaxBytes", cacheLimit);
    }

    /** Call inside the state's monitor before changing any snapshot-visible field. */
    public void invalidateSnapshot(String key) {
        synchronized (cacheLock) {
            Entry<V> entry = entries.get(key);
            if (entry != null && entry.serializedMember != null) {
                cacheBytes -= entry.serializedMember.length;
                entry.serializedMember = null;
            }
        }
    }

    /**
     * Compose the original JSON object in the same map iteration order. Only unchanged
     * per-key JSON members are reused; before/after rollback snapshots and digest bytes
     * are still complete independent byte arrays. Eviction/restore releases cache data.
     */
    public byte[] snapshot(Function<V, Object> encode) {
        cleanup(System.nanoTime());
        if (cacheLimit == 0) {
            Map<String, Object> values = new java.util.LinkedHashMap<>();
            entries.forEach((key, entry) -> {
                synchronized (entry.value) { values.put(key, encode.apply(entry.value)); }
            });
            return StateSnapshotCodec.write(values);
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write('{');
        boolean[] first = {true};
        entries.forEach((key, entry) -> {
            byte[] member;
            synchronized (entry.value) {
                synchronized (cacheLock) { member = entry.serializedMember; }
                if (member == null) {
                    byte[] name = StateSnapshotCodec.write(key);
                    byte[] value = StateSnapshotCodec.write(encode.apply(entry.value));
                    member = new byte[name.length + value.length + 1];
                    System.arraycopy(name, 0, member, 0, name.length);
                    member[name.length] = ':';
                    System.arraycopy(value, 0, member, name.length + 1, value.length);
                    synchronized (cacheLock) {
                        if (entries.get(key) == entry && entry.serializedMember == null
                                && member.length <= cacheLimit - cacheBytes) {
                            entry.serializedMember = member;
                            cacheBytes += member.length;
                        }
                    }
                }
            }
            if (!first[0]) output.write(',');
            first[0] = false;
            output.writeBytes(member);
        });
        output.write('}');
        return output.toByteArray();
    }

    private long cachedBytes() { synchronized (cacheLock) { return cacheBytes; } }

    private boolean remove(String key, Entry<V> entry) {
        synchronized (cacheLock) {
            if (!entries.remove(key, entry)) return false;
            if (entry.serializedMember != null) cacheBytes -= entry.serializedMember.length;
            entry.serializedMember = null;
            return true;
        }
    }

    private void cleanup(long now) {
        long cutoff = now - idleNanos;
        entries.forEach((key, entry) -> {
            if (entry.lastAccessNanos < cutoff && remove(key, entry)) evictions.incrementAndGet();
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
            if (remove(candidate.getKey(), candidate.getValue())) evictions.incrementAndGet();
        }
    }

    private static final class Entry<V> {
        private final V value;
        private byte[] serializedMember;
        private volatile long lastAccessNanos;

        private Entry(V value, long lastAccessNanos) {
            this.value = value;
            this.lastAccessNanos = lastAccessNanos;
        }
    }
}
