package com.socp.search.config.service;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 接入任务运行态监控。
 *
 * <p>接入配置页只回答"配了什么"，运维真正要问的是"现在还在收数据吗、每秒多少条、
 * 最后一条什么时候到的、有没有报错"。本组件按采集器标签（{@code collector}，
 * 即 LogSource.collectorTag()）累计运行指标，与配置 join 后构成完整的接入任务视图。
 *
 * <p>EPS 用秒级滑动桶计算（保留 300 秒），可同时给出 1 分钟 / 5 分钟速率，
 * 便于判断"是彻底断流还是只是变慢"。
 */
@Component
public class IngestTaskMonitor {

    /** 秒级桶保留时长 */
    private static final int WINDOW_SECONDS = 300;
    /** 超过该时长没有新数据视为断流 */
    private static final long STALE_SECONDS = 300;

    private final Map<String, Stat> stats = new ConcurrentHashMap<>();

    private static final class Stat {
        long accepted;
        long skipped;
        long forwarded;
        long bytes;
        Instant firstAt;
        Instant lastAt;
        String lastError;
        Instant lastErrorAt;
        /** [epochSecond, count]，按秒聚合 */
        final ArrayDeque<long[]> buckets = new ArrayDeque<>();
    }

    /** 记录一批投递的处理结果 */
    public void record(String collector, int accepted, int skipped, int forwarded, long bytes) {
        String key = normalize(collector);
        Stat s = stats.computeIfAbsent(key, k -> new Stat());
        Instant now = Instant.now();
        synchronized (s) {
            if (s.firstAt == null) s.firstAt = now;
            s.accepted += accepted;
            s.skipped += skipped;
            s.forwarded += forwarded;
            s.bytes += bytes;
            if (accepted > 0) s.lastAt = now;

            long sec = now.getEpochSecond();
            long[] tail = s.buckets.peekLast();
            if (tail != null && tail[0] == sec) tail[1] += accepted;
            else s.buckets.addLast(new long[]{sec, accepted});
            while (!s.buckets.isEmpty() && s.buckets.peekFirst()[0] < sec - WINDOW_SECONDS) {
                s.buckets.pollFirst();
            }
        }
    }

    public void recordError(String collector, String error) {
        Stat s = stats.computeIfAbsent(normalize(collector), k -> new Stat());
        synchronized (s) {
            s.lastError = error;
            s.lastErrorAt = Instant.now();
        }
    }

    /** 单个采集器的运行态快照；从未收到数据则返回全零快照 */
    public Map<String, Object> runtime(String collector, boolean enabled) {
        Stat s = stats.get(normalize(collector));
        Map<String, Object> m = new LinkedHashMap<>();
        if (s == null) {
            m.put("accepted", 0L);
            m.put("skipped", 0L);
            m.put("forwarded", 0L);
            m.put("bytes", 0L);
            m.put("eps1m", 0.0);
            m.put("eps5m", 0.0);
            m.put("firstAt", null);
            m.put("lastAt", null);
            m.put("lastError", null);
            m.put("health", enabled ? "IDLE" : "DISABLED");
            return m;
        }
        synchronized (s) {
            long now = Instant.now().getEpochSecond();
            m.put("accepted", s.accepted);
            m.put("skipped", s.skipped);
            m.put("forwarded", s.forwarded);
            m.put("bytes", s.bytes);
            m.put("eps1m", eps(s, now, 60));
            m.put("eps5m", eps(s, now, 300));
            m.put("firstAt", s.firstAt == null ? null : s.firstAt.toString());
            m.put("lastAt", s.lastAt == null ? null : s.lastAt.toString());
            m.put("lastError", s.lastError);
            m.put("lastErrorAt", s.lastErrorAt == null ? null : s.lastErrorAt.toString());
            m.put("health", health(s, enabled, now));
        }
        return m;
    }

    private static double eps(Stat s, long now, int seconds) {
        long sum = 0;
        for (long[] b : s.buckets) {
            if (b[0] > now - seconds) sum += b[1];
        }
        return Math.round((double) sum / seconds * 100) / 100.0;
    }

    private static String health(Stat s, boolean enabled, long now) {
        if (!enabled) return "DISABLED";
        if (s.lastAt == null) return "IDLE";
        if (s.lastErrorAt != null && s.lastErrorAt.getEpochSecond() > now - 60) return "ERROR";
        if (s.lastAt.getEpochSecond() < now - STALE_SECONDS) return "STALE";
        if (s.skipped > 0 && s.skipped > s.accepted) return "DEGRADED";
        return "HEALTHY";
    }

    /** 全局摘要：总接入量 + 总速率 + 各健康状态的采集器数量 */
    public Map<String, Object> summary(List<String> enabledCollectors) {
        long accepted = 0, skipped = 0, forwarded = 0, bytes = 0;
        double eps = 0;
        long now = Instant.now().getEpochSecond();
        Map<String, Integer> byHealth = new LinkedHashMap<>();
        for (String h : List.of("HEALTHY", "DEGRADED", "STALE", "IDLE", "ERROR", "DISABLED")) byHealth.put(h, 0);
        for (var e : stats.entrySet()) {
            Stat s = e.getValue();
            boolean enabled = enabledCollectors == null || enabledCollectors.contains(e.getKey());
            synchronized (s) {
                accepted += s.accepted;
                skipped += s.skipped;
                forwarded += s.forwarded;
                bytes += s.bytes;
                eps += eps(s, now, 60);
                byHealth.merge(health(s, enabled, now), 1, Integer::sum);
            }
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("collectors", stats.size());
        m.put("accepted", accepted);
        m.put("skipped", skipped);
        m.put("forwarded", forwarded);
        m.put("bytes", bytes);
        m.put("eps1m", Math.round(eps * 100) / 100.0);
        m.put("byHealth", byHealth);
        return m;
    }

    private static String normalize(String collector) {
        return collector == null || collector.isBlank() ? "unknown" : collector.trim().toLowerCase();
    }
}
