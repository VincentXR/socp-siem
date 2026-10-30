package com.socp.rule.state;

import com.socp.rule.engine.DetectionResult;

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
    private Mutation mutation;

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
        Entry<V> entry;
        synchronized (cacheLock) {
            entry = entries.get(key);
            // Full event snapshots used to expire idle keys before accept().
            // The incremental path must check the accessed key itself before
            // refreshing its age, otherwise an idle window can be resurrected.
            if (entry != null && entry.lastAccessNanos < now - idleNanos && remove(key, entry)) {
                evictions.incrementAndGet();
                entry = null;
            }
            if (mutation != null) {
                mutation.checkOwner();
                if (!mutation.before.containsKey(key)) {
                    mutation.remember(key, entry);
                    if (entry != null) {
                        Entry<V> copy = new Entry<>(mutation.copy.apply(entry.value), entry.lastAccessNanos);
                        copy.serializedMember = entry.serializedMember;
                        entries.put(key, copy);
                        entry = copy;
                    }
                }
            }
            if (entry == null) {
                entry = new Entry<>(factory.get(), now);
                entries.put(key, entry);
            }
        }
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
        synchronized (cacheLock) {
            if (mutation != null) throw new IllegalStateException("cannot restore during a state mutation");
            entries.clear();
            cacheBytes = 0;
        }
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
     * per-key JSON members are reused; checkpoint bytes remain complete independent
     * byte arrays. Eviction/restore releases cache data.
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
            if (mutation != null) {
                mutation.checkOwner();
                if (entries.get(key) == entry) mutation.remember(key, entry);
            }
            if (!entries.remove(key, entry)) return false;
            if (entry.serializedMember != null) cacheBytes -= entry.serializedMember.length;
            return true;
        }
    }

    /**
     * Copy only touched values; immutable fields (including SecurityEvent) may be
     * shared, mutable collections must be copied. The caller serializes access
     * to this map until close. Undo is bounded by maxKeys plus one insertion;
     * removals from TTL/capacity passes participate in the same transaction.
     * Full checkpoint bytes and their compatibility versions are unchanged.
     */
    public RuleStateMutation beginMutation(Function<V, V> copy, Function<V, Object> encode) {
        synchronized (cacheLock) {
            if (mutation != null) throw new IllegalStateException("state mutation already active");
            mutation = new Mutation(Objects.requireNonNull(copy), Objects.requireNonNull(encode));
            return mutation;
        }
    }

    private final class Mutation implements RuleStateMutation {
        private final Thread owner = Thread.currentThread();
        private final Map<String, Entry<V>> before = new java.util.LinkedHashMap<>();
        private final Function<V, V> copy;
        private final Function<V, Object> encode;
        private boolean committed;
        private boolean closed;

        private Mutation(Function<V, V> copy, Function<V, Object> encode) {
            this.copy = copy;
            this.encode = encode;
        }

        private void checkOwner() {
            if (Thread.currentThread() != owner || closed) {
                throw new IllegalStateException("state mutation requires the engine state lock");
            }
        }

        private void remember(String key, Entry<V> entry) {
            checkOwner();
            if (!before.containsKey(key)) {
                if (before.size() >= maxKeys + 1L) {
                    throw new IllegalStateException("too many keys in one rule mutation");
                }
                before.put(key, entry);
            }
        }

        @Override
        public DetectionResult.StateChange change(String ruleId) {
            checkOwner();
            Map<String, Object> original = new java.util.TreeMap<>();
            Map<String, Object> current = new java.util.TreeMap<>();
            before.forEach((key, entry) -> {
                if (entry != null) original.put(key, encode.apply(entry.value));
                Entry<V> after = entries.get(key);
                if (after != null) current.put(key, encode.apply(after.value));
            });
            byte[] left = StateSnapshotCodec.write(original);
            byte[] right = StateSnapshotCodec.write(current);
            // These digests describe the touched-key projection, not the full
            // checkpoint. A domain/version prefix prevents ambiguous comparison
            // with the historical full-snapshot SHA-256 audit values.
            return new DetectionResult.StateChange(ruleId,
                    "entity-delta-v1:sha256:" + DetectionResult.digest(left),
                    "entity-delta-v1:sha256:" + DetectionResult.digest(right),
                    !java.util.Arrays.equals(left, right));
        }

        @Override
        public void rollback() {
            checkOwner();
            synchronized (cacheLock) {
                before.forEach((key, entry) -> {
                    Entry<V> current = entries.get(key);
                    if (current != null && current.serializedMember != null) {
                        cacheBytes -= current.serializedMember.length;
                    }
                    if (entry == null) entries.remove(key);
                });
                before.forEach((key, entry) -> {
                    if (entry != null) {
                        if (entry.serializedMember != null) {
                            if (entry.serializedMember.length <= cacheLimit - cacheBytes) {
                                cacheBytes += entry.serializedMember.length;
                            } else {
                                entry.serializedMember = null;
                            }
                        }
                        entries.put(key, entry);
                    }
                });
                before.clear();
            }
        }

        @Override public void commit() { checkOwner(); committed = true; }

        @Override
        public void close() {
            if (closed) return;
            checkOwner();
            try {
                if (!committed) rollback();
            } finally {
                synchronized (cacheLock) { mutation = null; before.clear(); closed = true; }
            }
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
