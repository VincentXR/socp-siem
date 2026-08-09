package com.socp.rule.rules;

import com.socp.rule.model.Alert;
import com.socp.rule.model.SecurityEvent;
import com.socp.rule.model.Severity;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * 无序关联规则（跨事件）：同一实体在窗口内触发「一组条件」即告警，不要求先后顺序。
 * 与 CorrelationRule（有序事件链）互补——适合「A 和 B 都发生过，但先后无所谓」的场景，
 * 例如「同一 IP 既出现防火墙拦截、又出现认证成功」→ 疑似横向移动。
 *
 * <p>实现：每个实体维护一个 BitSet，命中第 i 个条件就置位第 i 位；全部置位即告警并重置。
 * 窗口过期则清零重来。由 com.siem 迁移。
 */
public final class CorrelationSetRule extends AbstractRule {

    private final Function<SecurityEvent, String> keyOf;
    private final List<Predicate<SecurityEvent>> conds;
    private final Duration window;
    private final Severity severity;
    private final String messageTemplate;

    private static final class State {
        final BitSet bits = new BitSet();
        Instant firstTs = null;
    }

    private final Map<String, State> states = new ConcurrentHashMap<>();

    public CorrelationSetRule(String id, String name,
                              Function<SecurityEvent, String> keyOf,
                              List<Predicate<SecurityEvent>> conds,
                              Duration window, Severity severity, String messageTemplate) {
        super(id, name);
        if (conds.isEmpty()) throw new IllegalArgumentException("无序关联规则至少需要一个条件");
        this.keyOf = keyOf;
        this.conds = List.copyOf(conds);
        this.window = window;
        this.severity = severity;
        this.messageTemplate = messageTemplate;
    }

    @Override
    public void accept(SecurityEvent event) {
        String key = keyOf.apply(event);
        if (key == null) return;

        State st = states.computeIfAbsent(key, k -> new State());
        synchronized (st) {
            if (st.firstTs != null && event.timestamp().minus(window).isAfter(st.firstTs)) {
                st.bits.clear();           // 窗口过期，重置
                st.firstTs = null;
            }
            if (st.firstTs == null) st.firstTs = event.timestamp();

            for (int i = 0; i < conds.size(); i++) {
                if (!st.bits.get(i) && conds.get(i).test(event)) {
                    st.bits.set(i);
                }
            }

            if (st.bits.cardinality() == conds.size()) {
                String msg = messageTemplate
                        .replace("{key}", key)
                        .replace("{count}", String.valueOf(conds.size()));
                List<SecurityEvent> evidence = new ArrayList<>();
                evidence.add(event);
                emit(new Alert(id, name, severity, msg, key, evidence));
                st.bits.clear();
                st.firstTs = null;
            }
        }
    }
}
