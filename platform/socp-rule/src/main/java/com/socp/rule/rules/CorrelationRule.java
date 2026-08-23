package com.socp.rule.rules;

import com.socp.rule.model.Alert;
import com.socp.rule.model.SecurityEvent;
import com.socp.rule.model.Severity;
import com.socp.rule.state.RuleStateMap;

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
public final class CorrelationRule extends AbstractRule {

    private final Function<SecurityEvent, String> keyOf;
    private final List<Predicate<SecurityEvent>> steps;
    private final Duration window;
    private final Severity severity;
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
        super(id, name);
        if (steps.isEmpty()) throw new IllegalArgumentException("关联规则至少需要一个步骤");
        this.keyOf = keyOf;
        this.steps = List.copyOf(steps);
        this.window = window;
        this.severity = severity;
        this.messageTemplate = messageTemplate;
    }

    @Override
    public void accept(SecurityEvent event) {
        String key = keyOf.apply(event);
        if (key == null) return;

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
                String msg = messageTemplate
                        .replace("{key}", key)
                        .replace("{count}", String.valueOf(steps.size()));
                emit(new Alert(id, name, severity, msg, key, new ArrayList<>(st.evidence)));
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
}
