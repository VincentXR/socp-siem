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
    private final Object mutationBoundary = new Object();
    private final long cacheLimit = Math.max(0, Math.min(64L * 1024 * 1024,
            Long.getLong("socp.rule.snapshot-cache.max-bytes", 8L * 1024 * 1024)));
    private long cacheBytes;
    private volatile Mutation activeMutation;
    private volatile Thread mutationOwner;
    private final java.util.concurrent.atomic.AtomicInteger openMutations = new java.util.concurrent.atomic.AtomicInteger();
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
        Mutation mutation = activeMutation;
        if (mutation != null) mutation.capture(key, entries.get(key));
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
        synchronized (mutationBoundary) {
            if (openMutations.get() != 0) throw new IllegalStateException("cannot clear state during evaluation");
            synchronized (cacheLock) { entries.clear(); cacheBytes = 0; }
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
     * per-key JSON members are reused. Event rollback uses detached trees and
     * Merkle fingerprints independently of this cache. Eviction/restore releases cache data.
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
        Mutation mutation = activeMutation;
        if (mutation != null) mutation.capture(key, entry);
        synchronized (cacheLock) {
            if (!entries.remove(key, entry)) return false;
            if (entry.serializedMember != null) cacheBytes -= entry.serializedMember.length;
            entry.serializedMember = null;
            return true;
        }
    }

    private void cleanup(long now) {
        // Keep the check and cleanup atomic with opening an undo boundary.
        // UI/stats readers must not start eviction just before evaluation opens it.
        synchronized (mutationBoundary) {
            if (openMutations.get() != 0 && mutationOwner != Thread.currentThread()) return;
            long cutoff = now - idleNanos;
            entries.forEach((key, entry) -> {
                if (entry.lastAccessNanos < cutoff && remove(key, entry)) evictions.incrementAndGet();
            });
        }
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

    /**
     * Capture only keys touched by this event, including capacity/idle evictions.
     * The engine serializes a tenant/shard's evaluation and owns this scope.
     * Checkpoints keep the original full JSON format; audit digests explicitly
     * identify their affected-key scope instead of pretending to hash full state.
     */
    public StateMutation beginMutation(Function<V, Object> encode, Function<Object, V> decode) {
        synchronized (mutationBoundary) {
            if (activeMutation != null || (openMutations.get() != 0 && mutationOwner != Thread.currentThread())) {
                throw new IllegalStateException("overlapping rule state mutation");
            }
            Mutation mutation = new Mutation(encode, decode);
            mutationOwner = Thread.currentThread();
            openMutations.incrementAndGet();
            activeMutation = mutation;
            return mutation;
        }
    }

    public void restore(byte[] serializedState, Function<Object, V> decode) {
        Map<String, Object> snapshot = StateSnapshotCodec.read(serializedState);
        clear();
        snapshot.forEach((key, raw) -> {
            if (key == null || key.isBlank()) return;
            V value = decode.apply(raw);
            if (value != null) get(key, () -> value);
        });
    }

    private final class Mutation implements StateMutation {
        private final Thread owner = Thread.currentThread();
        private final Function<V, Object> encode;
        private final Function<Object, V> decode;
        private final Map<String, Before> before = new java.util.LinkedHashMap<>();
        private boolean closed;

        private Mutation(Function<V, Object> encode, Function<Object, V> decode) {
            this.encode = Objects.requireNonNull(encode);
            this.decode = Objects.requireNonNull(decode);
        }

        private void checkOwner() {
            if (closed || owner != Thread.currentThread()) {
                throw new IllegalStateException("rule mutation must stay on its evaluation thread");
            }
        }

        private void capture(String key, Entry<V> entry) {
            checkOwner();
            if (before.containsKey(key)) return;
            if (entry == null) {
                before.put(key, new Before(null, null, 0, 0));
            } else {
                synchronized (entry.value) {
                    Object tree = StateSnapshotCodec.freeze(encode.apply(entry.value));
                    before.put(key, new Before(tree, StateSnapshotCodec.fingerprint(tree),
                            entry.lastAccessNanos, StateSnapshotCodec.retainedBytes(tree)));
                }
            }
        }

        @Override
        public com.socp.rule.engine.DetectionResult.StateChange change(String ruleId) {
            checkOwner();
            // Sorted keys and length-framed bytes make the scope independent of
            // hash-map order and unambiguous for missing versus present values.
            java.security.MessageDigest original = digest();
            java.security.MessageDigest current = digest();
            boolean changed = false;
            for (String key : new java.util.TreeSet<>(before.keySet())) {
                byte[] old = before.get(key).fingerprint;
                Entry<V> entry = entries.get(key);
                byte[] value = null;
                if (entry != null) {
                    synchronized (entry.value) { value = StateSnapshotCodec.fingerprint(encode.apply(entry.value)); }
                }
                update(original, key, old);
                update(current, key, value);
                changed |= !java.util.Arrays.equals(old, value);
            }
            return new com.socp.rule.engine.DetectionResult.StateChange(ruleId,
                    java.util.HexFormat.of().formatHex(original.digest()),
                    java.util.HexFormat.of().formatHex(current.digest()), changed, "affected-keys-merkle-v1");
        }

        @Override
        public void rollback() {
            checkOwner();
            for (Map.Entry<String, Before> item : before.entrySet()) {
                Before old = item.getValue();
                V restored = old.value == null ? null : decode.apply(old.value);
                if (old.value != null && restored == null) {
                    throw new IllegalStateException("unable to restore rule state key");
                }
                synchronized (cacheLock) {
                    Entry<V> removed = entries.remove(item.getKey());
                    if (removed != null && removed.serializedMember != null) {
                        cacheBytes -= removed.serializedMember.length;
                    }
                    if (restored != null) entries.put(item.getKey(), new Entry<>(restored, old.lastAccessNanos));
                }
            }
        }

        @Override
        public void detach() {
            checkOwner();
            if (activeMutation != this) throw new IllegalStateException("mutation is not active");
            activeMutation = null;
        }

        @Override
        public long retainedBytes() {
            return before.entrySet().stream().mapToLong(entry -> 128L + 2L * entry.getKey().length()
                    + entry.getValue().retainedBytes).sum();
        }

        @Override
        public void close() {
            checkOwner();
            synchronized (mutationBoundary) {
                if (activeMutation == this) activeMutation = null;
                before.clear();
                closed = true;
                if (openMutations.decrementAndGet() == 0) mutationOwner = null;
            }
        }
    }

    private static java.security.MessageDigest digest() {
        try { return java.security.MessageDigest.getInstance("SHA-256"); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private static void update(java.security.MessageDigest digest, String key, byte[] value) {
        byte[] name = key.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        digest.update(java.nio.ByteBuffer.allocate(4).putInt(name.length).array());
        digest.update(name);
        digest.update(java.nio.ByteBuffer.allocate(4).putInt(value == null ? -1 : value.length).array());
        if (value != null) digest.update(value);
    }

    private record Before(Object value, byte[] fingerprint, long lastAccessNanos, long retainedBytes) { }

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
