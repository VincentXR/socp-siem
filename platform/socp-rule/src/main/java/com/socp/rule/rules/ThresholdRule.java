package com.socp.rule.rules;

import com.socp.rule.model.Alert;
import com.socp.rule.model.SecurityEvent;
import com.socp.rule.model.Severity;
import com.socp.rule.state.RuleStateMap;
import com.socp.rule.state.StateSnapshotCodec;
import com.socp.rule.state.StatefulRule;
import com.socp.rule.time.EventTimePolicy;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * 阈值规则：对“同一个实体”（如同一个源 IP）在滑动时间窗口内累计命中事件，
 * 当数量达到阈值即告警。命中后清空窗口桶，避免刷屏式重复告警。
 * 典型场景：X 秒内同一 IP 失败登录 &gt;= N 次 =&gt; 暴力破解。由 com.siem 迁移。
 */
public final class ThresholdRule extends AbstractRule implements StatefulRule {

    private final Predicate<SecurityEvent> matcher;     // 哪些事件计入统计
    private final Function<SecurityEvent, String> keyOf; // 聚合维度
    private final int threshold;
    private final Duration window;
    private final Severity severity;
    private final String titleTemplate;
    private final String messageTemplate;

    // 每个实体维护一个时间戳窗口的事件队列
    private final RuleStateMap<BucketState> buckets = new RuleStateMap<>();
    private final EventTimePolicy eventTimePolicy;

    private static final class BucketState {
        final ArrayDeque<SecurityEvent> events = new ArrayDeque<>();
        Instant watermark;
    }

    public ThresholdRule(String id, String name,
                         Predicate<SecurityEvent> matcher,
                         Function<SecurityEvent, String> keyOf,
                         int threshold, Duration window,
                         Severity severity, String messageTemplate) {
        this(id, name, matcher, keyOf, threshold, window,
                EventTimePolicy.defaultFor(window), severity, name, messageTemplate);
    }

    public ThresholdRule(String id, String name,
                         Predicate<SecurityEvent> matcher,
                         Function<SecurityEvent, String> keyOf,
                         int threshold, Duration window,
                         Severity severity, String titleTemplate,
                         String messageTemplate) {
        this(id, name, matcher, keyOf, threshold, window,
                EventTimePolicy.defaultFor(window), severity, titleTemplate, messageTemplate);
    }

    public ThresholdRule(String id, String name,
                         Predicate<SecurityEvent> matcher,
                         Function<SecurityEvent, String> keyOf,
                         int threshold, Duration window, EventTimePolicy eventTimePolicy,
                         Severity severity, String titleTemplate,
                         String messageTemplate) {
        super(id, name);
        this.matcher = matcher;
        this.keyOf = keyOf;
        this.threshold = threshold;
        this.window = window;
        this.eventTimePolicy = eventTimePolicy == null
                ? EventTimePolicy.defaultFor(window) : eventTimePolicy;
        this.severity = severity;
        this.titleTemplate = titleTemplate;
        this.messageTemplate = messageTemplate;
    }

    @Override
    public void accept(SecurityEvent event) {
        if (!matcher.test(event)) return;
        String key = keyOf.apply(event);
        if (key == null || key.isBlank()) return;

        BucketState state = buckets.get(key, BucketState::new);
        synchronized (state) {
            if (eventTimePolicy.isLate(event.timestamp(), state.watermark)
                    && eventTimePolicy.handling() == EventTimePolicy.LateEventHandling.DROP) {
                return;
            }
            if (state.watermark == null || state.watermark.isBefore(event.timestamp())) {
                state.watermark = event.timestamp();
            }
            ArrayDeque<SecurityEvent> q = state.events;
            // 防御性内存保护：如果队列严重超出阈值上限，清理最旧事件防止内存膨胀
            int maxCap = Math.max(threshold * 2, 200);
            while (q.size() >= maxCap) {
                q.pollFirst();
            }
            q.add(event);
            // Kafka preserves order per partition, but a routed entity may be
            // observed from several partitions. Use the greatest event-time
            // watermark seen for this bucket so a late record cannot move the
            // window backwards or resurrect expired evidence.
            Instant cutoff = state.watermark.minus(window);
            q.removeIf(candidate -> candidate.timestamp().isBefore(cutoff));
            if (q.size() >= threshold) {
                List<SecurityEvent> evidence = new ArrayList<>(q);
                Map<String, Object> context = Map.of(
                        "key", key,
                        "count", q.size(),
                        "window", window.toSeconds() + "s");
                String title = AlertTemplateRenderer.render(titleTemplate, event, context);
                String msg = AlertTemplateRenderer.render(messageTemplate, event, context);
                emit(Alert.withUnorderedEvidence(id, name, severity, title, msg, key, evidence));
                q.clear(); // 清空，避免短时间内重复刷屏告警
            }
        }
    }

    @Override
    public Map<String, Object> stats() {
        Map<String, Object> out = super.stats();
        out.putAll(buckets.stats());
        return out;
    }

    @Override
    public String stateVersion() {
        return "threshold-v2";
    }

    @Override
    public byte[] snapshotState() {
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        buckets.forEach((key, state) -> {
            synchronized (state) {
                Map<String, Object> value = new java.util.LinkedHashMap<>();
                value.put("watermark", state.watermark == null ? null : state.watermark.toString());
                value.put("events", state.events.stream().map(StateSnapshotCodec::event).toList());
                out.put(key, value);
            }
        });
        return StateSnapshotCodec.write(out);
    }

    @Override
    public void restoreState(byte[] serializedState) {
        Map<String, Object> snapshot = StateSnapshotCodec.read(serializedState);
        buckets.clear();
        snapshot.forEach((key, raw) -> {
            BucketState state = buckets.get(key, BucketState::new);
            synchronized (state) {
                state.events.clear();
                List<?> items;
                if (raw instanceof Map<?, ?> values) {
                    state.watermark = parseInstant(values.get("watermark"));
                    Object events = values.get("events");
                    items = events instanceof List<?> list ? list : List.of();
                } else if (raw instanceof List<?> legacy) {
                    // Read v1 snapshots during an explicit compatibility
                    // migration; newly written snapshots always carry the
                    // watermark separately.
                    state.watermark = null;
                    items = legacy;
                } else {
                    return;
                }
                items.stream().map(StateSnapshotCodec::event).filter(java.util.Objects::nonNull)
                        .limit(Math.max(threshold * 2, 200)).forEach(event -> {
                            state.events.addLast(event);
                            if (state.watermark == null || state.watermark.isBefore(event.timestamp())) {
                                state.watermark = event.timestamp();
                            }
                        });
            }
        });
    }

    private static Instant parseInstant(Object value) {
        if (value == null) return null;
        try {
            return Instant.parse(String.valueOf(value));
        } catch (Exception ignored) {
            return null;
        }
    }
}
