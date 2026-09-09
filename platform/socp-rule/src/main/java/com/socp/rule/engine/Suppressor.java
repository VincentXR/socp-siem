package com.socp.rule.engine;

import com.socp.rule.model.Alert;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 告警去重/抑制器：同一 (规则 + 实体) 在抑制窗口内只放行第一条，
 * 其余标记为已抑制，避免同一攻击被刷屏式重复告警。
 * 另起后台线程定时清理过期条目，避免 lastFired 无界增长造成内存泄漏。
 * 由 com.siem 迁移。
 */
public final class Suppressor implements AutoCloseable {

    private static final int DEFAULT_MAX_ENTRIES = 100_000;
    private final ReentrantLock[] locks = java.util.stream.IntStream.range(0, 256)
            .mapToObj(i -> new ReentrantLock()).toArray(ReentrantLock[]::new);
    private final Duration window;
    private final int maxEntries;
    private final ConcurrentHashMap<String, Instant> lastFired = new ConcurrentHashMap<>();
    private final AtomicLong suppressed = new AtomicLong();
    private volatile boolean closed = false;
    private final Thread cleaner;

    public Suppressor(Duration window) {
        this(window, DEFAULT_MAX_ENTRIES);
    }

    /**
     * Creates a suppressor with a hard cardinality bound. The bound is useful
     * when rule/entity keys are attacker-controlled and the suppression window
     * has not had time to expire them yet.
     */
    public Suppressor(Duration window, int maxEntries) {
        if (window == null || window.isNegative()) {
            throw new IllegalArgumentException("window must be non-negative");
        }
        if (maxEntries < 1) {
            throw new IllegalArgumentException("maxEntries must be positive");
        }
        this.window = window;
        this.maxEntries = maxEntries;
        this.cleaner = Thread.startVirtualThread(this::cleanupLoop);
    }

    /** Compatibility path for callers whose delivery is already complete. */
    public boolean allow(Alert alert) {
        try (Batch batch = begin(List.of(alert))) {
            batch.commit();
            return !batch.alerts().isEmpty();
        }
    }

    /** Reserve suppression keys until the durable sink succeeds or fails. */
    public Batch begin(List<Alert> alerts) {
        return new Batch(alerts);
    }

    private String key(Alert alert) {
        String tenant = "default";
        if (alert.evidence() != null) {
            for (var event : alert.evidence()) {
                if (event != null) { tenant = event.tenantId(); break; }
            }
        }
        return tenant + "|" + alert.ruleId() + "|"
                + (alert.entity() == null ? alert.message() : alert.entity());
    }

    public final class Batch implements AutoCloseable {
        private final int[] stripes;
        private final List<Alert> selected = new ArrayList<>();
        private final Set<String> keys = new HashSet<>();
        private boolean closed;

        private Batch(List<Alert> candidates) {
            stripes = candidates.stream().map(Suppressor.this::key)
                    .mapToInt(key -> Math.floorMod(key.hashCode(), locks.length)).distinct().sorted().toArray();
            // Fixed order prevents deadlocks when an event emits multiple alerts.
            for (int stripe : stripes) locks[stripe].lock();
            Instant now = Instant.now();
            for (Alert alert : candidates) {
                String key = key(alert);
                Instant previous = lastFired.get(key);
                if ((previous != null && previous.plus(window).isAfter(now))
                        || (!window.isZero() && keys.contains(key))) {
                    suppressed.incrementAndGet();
                } else {
                    keys.add(key);
                    selected.add(alert);
                }
            }
        }

        public List<Alert> alerts() { return List.copyOf(selected); }

        public void commit() {
            if (closed) throw new IllegalStateException("suppression batch is closed");
            Instant now = Instant.now();
            for (String key : keys) lastFired.put(key, now);
            trimToLimit(now);
        }

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            for (int i = stripes.length - 1; i >= 0; i--) locks[stripes[i]].unlock();
        }
    }

    /** 定时清理过期条目：prev 早于 (now - window) 的条目已不可能再影响抑制判定 */
    private void cleanupLoop() {
        long sleepMs = window.toMillis();
        if (sleepMs <= 0) sleepMs = 1000; // 防止 window=0 时忙等
        try {
            while (!closed) {
                Thread.sleep(sleepMs);
                Instant cutoff = Instant.now().minus(window);
                lastFired.entrySet().removeIf(e -> e.getValue().isBefore(cutoff));
                trimToLimit(Instant.now());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public void close() {
        closed = true;
        cleaner.interrupt();
    }

    public long suppressed() {
        return suppressed.get();
    }

    int trackedKeys() {
        return lastFired.size();
    }

    private void trimToLimit(Instant now) {
        if (lastFired.size() <= maxEntries) return;
        Instant cutoff = now.minus(window);
        lastFired.entrySet().removeIf(e -> e.getValue().isBefore(cutoff));
        int excess = lastFired.size() - maxEntries;
        if (excess <= 0) return;
        lastFired.entrySet().stream()
                .sorted(Comparator.comparing(java.util.Map.Entry::getValue))
                .limit(excess)
                .forEach(entry -> lastFired.remove(entry.getKey(), entry.getValue()));
    }
}
