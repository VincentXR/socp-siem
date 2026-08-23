package com.socp.rule.engine;

import com.socp.rule.model.Alert;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 告警去重/抑制器：同一 (规则 + 实体) 在抑制窗口内只放行第一条，
 * 其余标记为已抑制，避免同一攻击被刷屏式重复告警。
 * 另起后台线程定时清理过期条目，避免 lastFired 无界增长造成内存泄漏。
 * 由 com.siem 迁移。
 */
public final class Suppressor {

    private final Duration window;
    private final ConcurrentHashMap<String, Instant> lastFired = new ConcurrentHashMap<>();
    private final AtomicLong suppressed = new AtomicLong();
    private volatile boolean closed = false;
    private final Thread cleaner;

    public Suppressor(Duration window) {
        this.window = window;
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
}
