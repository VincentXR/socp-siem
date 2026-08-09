package com.socp.detect.model.engine;

import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 告警窗口聚合器（DETECT Model 核心）：
 *
 * <p>对二次分析后的告警做 <b>滑动时间窗口</b> 统计——按规则/实体/严重级别
 * 在 5 分钟窗口内的命中次数与趋势。生产环境消费 Kafka 告警流做窗口聚合
 * （Flink/Spark 语义），当前为进程内环形窗口实现，语义等价。
 */
@Component
@EnableScheduling
public class AlertWindowAggregator {

    /** 窗口时长（分钟）。 */
    public static final long WINDOW_MINUTES = 5;

    /** 环形时间桶：每 30 秒一个桶，覆盖 5 分钟 = 10 个桶。 */
    private static final int BUCKETS = 10;
    private static final long BUCKET_MS = 30_000;

    private final Deque<WindowBucket> ring = new ArrayDeque<>();
    private WindowBucket current;

    public AlertWindowAggregator() {
        current = new WindowBucket(nowBucketKey());
        ring.add(current);
    }

    private record WindowBucket(long key, Map<String, Long> byRule, Map<String, Long> byEntity,
                                Map<String, Long> bySeverity, long total) {
        WindowBucket(long key) {
            this(key, new ConcurrentHashMap<>(), new ConcurrentHashMap<>(),
                    new ConcurrentHashMap<>(), 0);
        }

        WindowBucket inc(String rule, String entity, String severity) {
            byRule.merge(rule == null ? "UNKNOWN" : rule, 1L, Long::sum);
            byEntity.merge(entity == null ? "UNKNOWN" : entity, 1L, Long::sum);
            bySeverity.merge(severity == null ? "INFO" : severity, 1L, Long::sum);
            return new WindowBucket(key, byRule, byEntity, bySeverity, total + 1);
        }
    }

    private static long nowBucketKey() {
        return Instant.now().toEpochMilli() / BUCKET_MS;
    }

    /** 记录一条二次分析命中的告警（由 ModelController.analyze 调用）。 */
    public void record(String ruleId, String entity, String severity) {
        roll();
        current = current.inc(ruleId, entity, severity);
        // 将新桶放回队尾
        ring.pollLast();
        ring.addLast(current);
    }

    /** 滚动窗口：丢弃过期桶。 */
    private synchronized void roll() {
        long now = nowBucketKey();
        while (!ring.isEmpty() && ring.peekFirst().key() < now - (BUCKETS - 1)) {
            ring.pollFirst();
        }
        if (ring.isEmpty() || ring.peekLast().key() < now) {
            WindowBucket nb = new WindowBucket(now);
            ring.addLast(nb);
            current = nb;
        }
    }

    /** 当前 5 分钟窗口内的聚合结果（含趋势：与上一窗口对比）。 */
    public Map<String, Object> snapshot() {
        roll();
        Map<String, Long> byRule = new LinkedHashMap<>();
        Map<String, Long> byEntity = new LinkedHashMap<>();
        Map<String, Long> bySeverity = new LinkedHashMap<>();
        long total = 0;
        for (WindowBucket b : ring) {
            b.byRule().forEach((k, v) -> byRule.merge(k, v, Long::sum));
            b.byEntity().forEach((k, v) -> byEntity.merge(k, v, Long::sum));
            b.bySeverity().forEach((k, v) -> bySeverity.merge(k, v, Long::sum));
            total += b.total();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("window", WINDOW_MINUTES + "m");
        out.put("total", total);
        out.put("byRule", sortedTop(byRule, 10));
        out.put("byEntity", sortedTop(byEntity, 10));
        out.put("bySeverity", bySeverity);
        out.put("trend", trend());
        return out;
    }

    /** 趋势：按分钟的历史命中数（最近 5 分钟）。 */
    public List<Map<String, Object>> trend() {
        roll();
        Map<Long, Long> byMinute = new LinkedHashMap<>();
        for (WindowBucket b : ring) {
            long minute = b.key() * BUCKET_MS / 60_000;
            byMinute.merge(minute, b.total(), Long::sum);
        }
        long nowMinute = Instant.now().truncatedTo(ChronoUnit.MINUTES).getEpochSecond() / 60;
        List<Map<String, Object>> trend = new java.util.ArrayList<>();
        for (long m = nowMinute - 4; m <= nowMinute; m++) {
            Map<String, Object> point = new LinkedHashMap<>();
            point.put("minute", Instant.ofEpochSecond(m * 60).toString().substring(11, 16));
            point.put("count", byMinute.getOrDefault(m, 0L));
            trend.add(point);
        }
        return trend;
    }

    private static Map<String, Object> sortedTop(Map<String, Long> src, int n) {
        return src.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(n)
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (a, b) -> a, LinkedHashMap::new));
    }

    /** 每 30 秒清理一次过期桶（与 BUCKET_MS 对齐，主动滚动）。 */
    @Scheduled(fixedDelay = BUCKET_MS, initialDelay = BUCKET_MS)
    public void tick() {
        roll();
    }
}
