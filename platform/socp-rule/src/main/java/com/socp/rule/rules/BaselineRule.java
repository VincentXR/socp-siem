package com.socp.rule.rules;

import com.socp.rule.model.Alert;
import com.socp.rule.model.SecurityEvent;
import com.socp.rule.model.Severity;
import com.socp.rule.state.RuleStateMap;
import com.socp.rule.state.StateSnapshotCodec;
import com.socp.rule.state.StatefulRule;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * UEBA 基线异常规则（z-score 离群检测）。
 *
 * <p>与阈值规则的本质区别：阈值是"人拍脑袋定的绝对数"，基线是"跟这个实体自己的历史比"。
 * 同样是 1 小时 200 次访问，对日常 5 次的财务账号是重大异常，对日常 3000 次的采集账号是正常。
 *
 * <p>算法：把时间切成等长的桶（bucket = window），为每个实体维护最近 N 个历史桶的计数序列，
 * 计算均值 μ 与标准差 σ；当"当前桶计数" &gt; μ + k·σ 且 ≥ minCount 时判定为异常并告警。
 * <ul>
 *   <li>学习期（历史桶数 &lt; warmup）只积累基线不告警，避免冷启动误报；</li>
 *   <li>σ 设下限 1.0，避免历史极稳定（σ≈0）时任何微小波动都触发；</li>
 *   <li>同一个桶内只告警一次，避免刷屏。</li>
 * </ul>
 */
public final class BaselineRule extends AbstractRule implements StatefulRule {

    /** 标准差下限，防止零方差导致过敏感 */
    private static final double MIN_STDDEV = 1.0;
    /** 单桶保留的证据事件上限 */
    private static final int MAX_EVIDENCE = 50;

    private final Predicate<SecurityEvent> matcher;
    private final Function<SecurityEvent, String> keyOf;
    private final long bucketSeconds;
    private final int baselineWindows;  // 参与基线计算的历史桶数
    private final int warmup;           // 学习期桶数
    private final double sigma;         // 触发倍数 k
    private final int minCount;         // 绝对下限，过滤低频噪声
    private final Severity severity;
    private final String titleTemplate;
    private final String messageTemplate;

    private final RuleStateMap<State> states = new RuleStateMap<>();

    private static final class State {
        long bucketIdx = Long.MIN_VALUE;
        int count;
        boolean alerted;
        final ArrayDeque<Integer> history = new ArrayDeque<>();
        final ArrayDeque<SecurityEvent> evidence = new ArrayDeque<>();
    }

    public BaselineRule(String id, String name,
                        Predicate<SecurityEvent> matcher,
                        Function<SecurityEvent, String> keyOf,
                        Duration window, int baselineWindows, int warmup,
                        double sigma, int minCount,
                        Severity severity, String messageTemplate) {
        this(id, name, matcher, keyOf, window, baselineWindows, warmup,
                sigma, minCount, severity, name, messageTemplate);
    }

    public BaselineRule(String id, String name,
                        Predicate<SecurityEvent> matcher,
                        Function<SecurityEvent, String> keyOf,
                        Duration window, int baselineWindows, int warmup,
                        double sigma, int minCount,
                        Severity severity, String titleTemplate,
                        String messageTemplate) {
        super(id, name);
        this.matcher = matcher;
        this.keyOf = keyOf;
        this.bucketSeconds = Math.max(1, window.toSeconds());
        this.baselineWindows = Math.max(2, baselineWindows);
        this.warmup = Math.max(2, Math.min(warmup, this.baselineWindows));
        this.sigma = sigma <= 0 ? 3.0 : sigma;
        this.minCount = Math.max(1, minCount);
        this.severity = severity;
        this.titleTemplate = titleTemplate;
        this.messageTemplate = messageTemplate;
    }

    @Override
    public void accept(SecurityEvent event) {
        if (!matcher.test(event)) return;
        String key = keyOf.apply(event);
        if (key == null || key.isBlank()) return;

        State st = states.get(key, State::new);
        synchronized (st) {
            long idx = event.timestamp().getEpochSecond() / bucketSeconds;
            if (idx != st.bucketIdx) {
                // 桶滚动：把上一个桶的计数沉淀进历史基线
                if (st.bucketIdx != Long.MIN_VALUE) {
                    st.history.addLast(st.count);
                    while (st.history.size() > baselineWindows) st.history.pollFirst();
                }
                st.bucketIdx = idx;
                st.count = 0;
                st.alerted = false;
                st.evidence.clear();
            }
            st.count++;
            if (st.evidence.size() < MAX_EVIDENCE) st.evidence.addLast(event);

            if (st.alerted || st.history.size() < warmup || st.count < minCount) return;

            double mean = mean(st.history);
            double sd = Math.max(MIN_STDDEV, stddev(st.history, mean));
            double trigger = mean + sigma * sd;
            if (st.count <= trigger) return;

            double z = (st.count - mean) / sd;
            Map<String, Object> context = Map.of(
                    "key", key,
                    "count", st.count,
                    "baseline", String.format("%.1f", mean),
                    "stddev", String.format("%.1f", sd),
                    "sigma", String.format("%.1f", sigma),
                    "z", String.format("%.1f", z),
                    "window", bucketSeconds + "s");
            String title = AlertTemplateRenderer.render(titleTemplate, event, context);
            String msg = AlertTemplateRenderer.render(messageTemplate, event, context);
            emit(new Alert(id, name, severity, title, msg, key, new ArrayList<>(st.evidence)));
            st.alerted = true;
        }
    }

    private static double mean(ArrayDeque<Integer> xs) {
        double s = 0;
        for (int x : xs) s += x;
        return s / xs.size();
    }

    private static double stddev(ArrayDeque<Integer> xs, double mean) {
        if (xs.size() < 2) return 0;
        double s = 0;
        for (int x : xs) s += (x - mean) * (x - mean);
        return Math.sqrt(s / (xs.size() - 1));
    }

    /** 暴露基线快照，供 UEBA 看板展示"这个实体的正常水位是多少" */
    public List<Map<String, Object>> snapshot() {
        List<Map<String, Object>> out = new ArrayList<>();
        states.forEach((k, st) -> {
            synchronized (st) {
                if (st.history.isEmpty()) return;
                double mean = mean(st.history);
                Map<String, Object> m = new java.util.LinkedHashMap<>();
                m.put("entity", k);
                m.put("current", st.count);
                m.put("baseline", Math.round(mean * 10) / 10.0);
                m.put("stddev", Math.round(Math.max(MIN_STDDEV, stddev(st.history, mean)) * 10) / 10.0);
                m.put("samples", st.history.size());
                out.add(m);
            }
        });
        return out;
    }

    @Override
    public String stateVersion() {
        return "baseline-v1";
    }

    @Override
    public byte[] snapshotState() {
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        states.forEach((key, state) -> {
            synchronized (state) {
                Map<String, Object> value = new java.util.LinkedHashMap<>();
                value.put("bucketIdx", state.bucketIdx);
                value.put("count", state.count);
                value.put("alerted", state.alerted);
                value.put("history", new ArrayList<>(state.history));
                value.put("evidence", state.evidence.stream().map(StateSnapshotCodec::event).toList());
                out.put(key, value);
            }
        });
        return StateSnapshotCodec.write(out);
    }

    @Override
    @SuppressWarnings("unchecked")
    public void restoreState(byte[] serializedState) {
        StateSnapshotCodec.read(serializedState).forEach((key, raw) -> {
            if (!(raw instanceof Map<?, ?> values)) return;
            State state = states.get(key, State::new);
            synchronized (state) {
                state.bucketIdx = number(values.get("bucketIdx"), Long.MIN_VALUE);
                state.count = (int) number(values.get("count"), 0);
                state.alerted = Boolean.TRUE.equals(values.get("alerted"));
                state.history.clear();
                Object history = values.get("history");
                if (history instanceof List<?> items) {
                    items.forEach(item -> state.history.addLast((int) number(item, 0)));
                }
                state.evidence.clear();
                Object evidence = values.get("evidence");
                if (evidence instanceof List<?> items) {
                    items.stream().map(StateSnapshotCodec::event).filter(java.util.Objects::nonNull)
                            .limit(MAX_EVIDENCE).forEach(state.evidence::addLast);
                }
            }
        });
    }

    private static long number(Object value, long fallback) {
        return value instanceof Number number ? number.longValue() : fallback;
    }

    @Override
    public Map<String, Object> stats() {
        Map<String, Object> out = super.stats();
        out.putAll(states.stats());
        return out;
    }
}
