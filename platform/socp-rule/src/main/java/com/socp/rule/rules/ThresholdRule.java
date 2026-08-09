package com.socp.rule.rules;

import com.socp.rule.model.Alert;
import com.socp.rule.model.SecurityEvent;
import com.socp.rule.model.Severity;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * 阈值规则：对“同一个实体”（如同一个源 IP）在滑动时间窗口内累计命中事件，
 * 当数量达到阈值即告警。命中后清空窗口桶，避免刷屏式重复告警。
 * 典型场景：X 秒内同一 IP 失败登录 &gt;= N 次 =&gt; 暴力破解。由 com.siem 迁移。
 */
public final class ThresholdRule extends AbstractRule {

    private final Predicate<SecurityEvent> matcher;     // 哪些事件计入统计
    private final Function<SecurityEvent, String> keyOf; // 聚合维度
    private final int threshold;
    private final Duration window;
    private final Severity severity;
    private final String messageTemplate;

    // 每个实体维护一个时间戳窗口的事件队列
    private final Map<String, ArrayDeque<SecurityEvent>> buckets = new ConcurrentHashMap<>();

    public ThresholdRule(String id, String name,
                         Predicate<SecurityEvent> matcher,
                         Function<SecurityEvent, String> keyOf,
                         int threshold, Duration window,
                         Severity severity, String messageTemplate) {
        super(id, name);
        this.matcher = matcher;
        this.keyOf = keyOf;
        this.threshold = threshold;
        this.window = window;
        this.severity = severity;
        this.messageTemplate = messageTemplate;
    }

    @Override
    public void accept(SecurityEvent event) {
        if (!matcher.test(event)) return;
        String key = keyOf.apply(event);
        if (key == null) return;

        ArrayDeque<SecurityEvent> q = buckets.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (q) {
            q.add(event);
            // 清理窗口外的旧事件
            Instant cutoff = event.timestamp().minus(window);
            while (!q.isEmpty() && q.peekFirst().timestamp().isBefore(cutoff)) {
                q.pollFirst();
            }
            if (q.size() >= threshold) {
                List<SecurityEvent> evidence = new ArrayList<>(q);
                String msg = messageTemplate
                        .replace("{key}", key)
                        .replace("{count}", String.valueOf(q.size()))
                        .replace("{window}", window.toSeconds() + "s");
                emit(new Alert(id, name, severity, msg, key, evidence));
                q.clear(); // 清空，避免短时间内重复刷屏告警
            }
        }
    }
}
