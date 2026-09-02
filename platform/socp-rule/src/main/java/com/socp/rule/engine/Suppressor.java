package com.socp.rule.engine;

import com.socp.rule.model.Alert;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
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

    /** 返回 true 表示放行；false 表示被抑制 */
    public boolean allow(Alert alert) {
        String tenant = "default";
        if (alert.evidence() != null) {
            for (var event : alert.evidence()) {
                if (event != null) {
                    tenant = event.tenantId();
                    break;
                }
            }
        }
        String key = tenant + "|" + alert.ruleId() + "|"
                + (alert.entity() == null ? alert.message() : alert.entity());
        Instant now = Instant.now();
        Instant prev = lastFired.get(key);
        if (prev != null && prev.plus(window).isAfter(now)) {
            suppressed.incrementAndGet();
            return false;
        }
        lastFired.put(key, now);
        trimToLimit(now);
        return true;
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
