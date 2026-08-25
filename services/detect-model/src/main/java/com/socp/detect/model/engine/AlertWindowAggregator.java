package com.socp.detect.model.engine;

import com.socp.platform.tenant.context.TenantContext;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/** Five-minute secondary-analysis window, independently maintained per tenant. */
@Component
@EnableScheduling
public class AlertWindowAggregator {

    public static final long WINDOW_MINUTES = 5;
    private static final int BUCKETS = 10;
    private static final long BUCKET_MS = 30_000;

    private final Map<String, TenantWindow> windows = new ConcurrentHashMap<>();

    @Value("${socp.detect.model.window-idle-ttl-ms:1800000}")
    private long idleTtlMs = 30 * 60 * 1000L;

    @Value("${socp.detect.model.window-max-tenants:1000}")
    private int maxTenants = 1000;

    private static final class TenantWindow {
        private final Deque<WindowBucket> ring = new ArrayDeque<>();
        private WindowBucket current;
        private volatile long lastAccessMillis = System.currentTimeMillis();

        private TenantWindow() {
            current = new WindowBucket(nowBucketKey());
            ring.add(current);
        }
    }

    private record WindowBucket(long key, Map<String, Long> byRule, Map<String, Long> byEntity,
                                Map<String, Long> bySeverity, long total) {
        private WindowBucket(long key) {
            this(key, new LinkedHashMap<>(), new LinkedHashMap<>(), new LinkedHashMap<>(), 0);
        }

        private WindowBucket inc(String rule, String entity, String severity) {
            byRule.merge(rule == null ? "UNKNOWN" : rule, 1L, Long::sum);
            byEntity.merge(entity == null ? "UNKNOWN" : entity, 1L, Long::sum);
            bySeverity.merge(severity == null ? "INFO" : severity, 1L, Long::sum);
            return new WindowBucket(key, byRule, byEntity, bySeverity, total + 1);
        }
    }

    public void record(String ruleId, String entity, String severity) {
        record(currentTenant(), ruleId, entity, severity);
    }

    public void record(String tenantId, String ruleId, String entity, String severity) {
        TenantWindow window = window(tenantId);
        synchronized (window) {
            roll(window);
            window.current = window.current.inc(ruleId, entity, severity);
            window.ring.pollLast();
            window.ring.addLast(window.current);
        }
    }

    public Map<String, Object> snapshot() {
        return snapshot(currentTenant());
    }

    public Map<String, Object> snapshot(String tenantId) {
        TenantWindow window = window(tenantId);
        synchronized (window) {
            roll(window);
            Map<String, Long> byRule = new LinkedHashMap<>();
            Map<String, Long> byEntity = new LinkedHashMap<>();
            Map<String, Long> bySeverity = new LinkedHashMap<>();
            long total = 0;
            for (WindowBucket bucket : window.ring) {
                bucket.byRule().forEach((key, value) -> byRule.merge(key, value, Long::sum));
                bucket.byEntity().forEach((key, value) -> byEntity.merge(key, value, Long::sum));
                bucket.bySeverity().forEach((key, value) -> bySeverity.merge(key, value, Long::sum));
                total += bucket.total();
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("window", WINDOW_MINUTES + "m");
            result.put("total", total);
            result.put("byRule", sortedTop(byRule, 10));
            result.put("byEntity", sortedTop(byEntity, 10));
            result.put("bySeverity", bySeverity);
            result.put("trend", trend(window));
            return result;
        }
    }

    public List<Map<String, Object>> trend() {
        return trend(currentTenant());
    }

    public List<Map<String, Object>> trend(String tenantId) {
        TenantWindow window = window(tenantId);
        synchronized (window) {
            roll(window);
            return trend(window);
        }
    }

    @Scheduled(fixedDelay = BUCKET_MS, initialDelay = BUCKET_MS)
    public void tick() {
        long now = System.currentTimeMillis();
        long safeTtl = Math.max(BUCKET_MS * BUCKETS, idleTtlMs);
        for (var entry : windows.entrySet()) {
            TenantWindow window = entry.getValue();
            if (now - window.lastAccessMillis > safeTtl) {
                windows.remove(entry.getKey(), window);
                continue;
            }
            synchronized (window) {
                roll(window);
            }
        }
        int excess = windows.size() - Math.max(1, maxTenants);
        if (excess > 0) {
            windows.entrySet().stream()
                    .sorted(Map.Entry.comparingByValue(
                            java.util.Comparator.comparingLong(value -> value.lastAccessMillis)))
                    .limit(excess)
                    .forEach(entry -> windows.remove(entry.getKey(), entry.getValue()));
        }
    }

    private TenantWindow window(String tenantId) {
        TenantWindow window = windows.computeIfAbsent(normalizeTenant(tenantId), ignored -> new TenantWindow());
        window.lastAccessMillis = System.currentTimeMillis();
        return window;
    }

    int cachedTenantWindows() {
        return windows.size();
    }

    private static void roll(TenantWindow window) {
        long now = nowBucketKey();
        while (!window.ring.isEmpty() && window.ring.peekFirst().key() < now - (BUCKETS - 1)) {
            window.ring.pollFirst();
        }
        if (window.ring.isEmpty() || window.ring.peekLast().key() < now) {
            WindowBucket next = new WindowBucket(now);
            window.ring.addLast(next);
            window.current = next;
        }
    }

    private static List<Map<String, Object>> trend(TenantWindow window) {
        Map<Long, Long> byMinute = new LinkedHashMap<>();
        for (WindowBucket bucket : window.ring) {
            long minute = bucket.key() * BUCKET_MS / 60_000;
            byMinute.merge(minute, bucket.total(), Long::sum);
        }
        long nowMinute = Instant.now().truncatedTo(ChronoUnit.MINUTES).getEpochSecond() / 60;
        List<Map<String, Object>> result = new java.util.ArrayList<>();
        for (long minute = nowMinute - 4; minute <= nowMinute; minute++) {
            Map<String, Object> point = new LinkedHashMap<>();
            point.put("minute", Instant.ofEpochSecond(minute * 60).toString().substring(11, 16));
            point.put("count", byMinute.getOrDefault(minute, 0L));
            result.add(point);
        }
        return result;
    }

    private static Map<String, Object> sortedTop(Map<String, Long> source, int limit) {
        return source.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(limit)
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue,
                        (left, right) -> left, LinkedHashMap::new));
    }

    private static long nowBucketKey() {
        return Instant.now().toEpochMilli() / BUCKET_MS;
    }

    private static String currentTenant() {
        return normalizeTenant(TenantContext.get());
    }

    private static String normalizeTenant(String tenant) {
        return tenant == null || tenant.isBlank() ? "default" : tenant;
    }
}
