package com.socp.rule.rules;

import com.socp.rule.model.Alert;
import com.socp.rule.model.SecurityEvent;
import com.socp.rule.model.Severity;
import com.socp.rule.state.RuleStateMap;
import com.socp.rule.state.StateSnapshotCodec;
import com.socp.rule.state.StatefulRule;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * 关联规则：检测“同一实体在窗口内依次发生的一系列事件”（事件链/攻击链）。
 * 典型场景：同一 IP 先多次失败登录，随后一次成功登录 =&gt; 疑似暴力破解得手。
 *
 * steps 为有序谓词列表，必须按序命中；窗口内未走完则过期重置。由 com.siem 迁移。
 */
public final class CorrelationRule extends AbstractRule implements StatefulRule {

    private final Function<SecurityEvent, String> keyOf;
    private final List<Predicate<SecurityEvent>> steps;
    private final Duration window;
    private final Severity severity;
    private final String titleTemplate;
    private final String messageTemplate;

    private static final class State {
        int step = 0;
        Instant firstTs = null;
        Instant lastTs = null;
        final List<SecurityEvent> evidence = new ArrayList<>();
    }

    private final RuleStateMap<State> states = new RuleStateMap<>();

    public CorrelationRule(String id, String name,
                           Function<SecurityEvent, String> keyOf,
                           List<Predicate<SecurityEvent>> steps,
                           Duration window, Severity severity, String messageTemplate) {
        this(id, name, keyOf, steps, window, severity, name, messageTemplate);
    }

    public CorrelationRule(String id, String name,
                           Function<SecurityEvent, String> keyOf,
                           List<Predicate<SecurityEvent>> steps,
                           Duration window, Severity severity,
                           String titleTemplate, String messageTemplate) {
        super(id, name);
        if (steps.isEmpty()) throw new IllegalArgumentException("关联规则至少需要一个步骤");
        this.keyOf = keyOf;
        this.steps = List.copyOf(steps);
        this.window = window;
        this.severity = severity;
        this.titleTemplate = titleTemplate;
        this.messageTemplate = messageTemplate;
    }

    @Override
    public void accept(SecurityEvent event) {
        String key = keyOf.apply(event);
        if (key == null || key.isBlank()) return;

        State st = states.get(key, State::new);
        synchronized (st) {
            // 窗口过期则重置
            if (st.firstTs != null && event.timestamp().minus(window).isAfter(st.firstTs)) {
                reset(st);
            }

            boolean matched;
            if (st.step == 0) {
                matched = steps.get(0).test(event);
            } else if (st.step < steps.size()) {
                matched = steps.get(st.step).test(event);
            } else {
                matched = false;
            }

            // 当前步不匹配，但可以从头重开
            if (!matched && steps.get(0).test(event)) {
                reset(st);
                matched = true;
            }

            if (!matched) return;

            if (st.step == 0) {
                st.firstTs = event.timestamp();
            }
            st.evidence.add(event);
            st.step++;
            st.lastTs = event.timestamp();

            if (st.step == steps.size()) {
                Map<String, Object> context = Map.of(
                        "key", key,
                        "count", steps.size(),
                        "window", window.toSeconds() + "s");
                String title = AlertTemplateRenderer.render(titleTemplate, event, context);
                String msg = AlertTemplateRenderer.render(messageTemplate, event, context);
                emit(new Alert(id, name, severity, title, msg, key, new ArrayList<>(st.evidence)));
                reset(st);
            }
        }
    }

    private static void reset(State st) {
        st.step = 0;
        st.firstTs = null;
        st.lastTs = null;
        st.evidence.clear();
    }

    @Override
    public Map<String, Object> stats() {
        Map<String, Object> out = super.stats();
        out.putAll(states.stats());
        return out;
    }

    @Override
    public String stateVersion() {
        return "correlation-v1";
    }

    @Override
    public byte[] snapshotState() {
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        states.forEach((key, state) -> {
            synchronized (state) {
                Map<String, Object> value = new java.util.LinkedHashMap<>();
                value.put("step", state.step);
                value.put("firstTs", state.firstTs == null ? null : state.firstTs.toString());
                value.put("lastTs", state.lastTs == null ? null : state.lastTs.toString());
                value.put("evidence", state.evidence.stream().map(StateSnapshotCodec::event).toList());
                out.put(key, value);
            }
        });
        return StateSnapshotCodec.write(out);
    }

    @Override
    public void restoreState(byte[] serializedState) {
        StateSnapshotCodec.read(serializedState).forEach((key, raw) -> {
            if (!(raw instanceof Map<?, ?> values)) return;
            State state = states.get(key, State::new);
            synchronized (state) {
                state.step = values.get("step") instanceof Number n ? n.intValue() : 0;
                state.firstTs = parseInstant(values.get("firstTs"));
                state.lastTs = parseInstant(values.get("lastTs"));
                state.evidence.clear();
                Object evidence = values.get("evidence");
                if (evidence instanceof List<?> items) {
                    items.stream().map(StateSnapshotCodec::event).filter(java.util.Objects::nonNull)
                            .limit(Math.max(1, steps.size())).forEach(state.evidence::add);
                }
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
