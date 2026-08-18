package com.socp.rule.rules;

import com.socp.rule.model.Alert;
import com.socp.rule.model.SecurityEvent;
import com.socp.rule.model.Severity;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Predicate;

/** Unordered multi-condition correlation rule. */
public final class CorrelationSetRule extends AbstractRule {

    private final Function<SecurityEvent, String> keyOf;
    private final List<Predicate<SecurityEvent>> conds;
    private final Duration window;
    private final Severity severity;
    private final String messageTemplate;

    private static final class State {
        final BitSet bits = new BitSet();
        Instant firstTs;
        final Map<Integer, SecurityEvent> evidence = new LinkedHashMap<>();
    }

    private final Map<String, State> states = new ConcurrentHashMap<>();

    public CorrelationSetRule(String id, String name,
                              Function<SecurityEvent, String> keyOf,
                              List<Predicate<SecurityEvent>> conds,
                              Duration window, Severity severity, String messageTemplate) {
        super(id, name);
        if (conds.isEmpty()) throw new IllegalArgumentException("correlation-set requires a condition");
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
                st.bits.clear();
                st.evidence.clear();
                st.firstTs = null;
            }
            if (st.firstTs == null) st.firstTs = event.timestamp();

            for (int i = 0; i < conds.size(); i++) {
                if (!st.bits.get(i) && conds.get(i).test(event)) {
                    st.bits.set(i);
                    st.evidence.put(i, event);
                }
            }

            if (st.bits.cardinality() == conds.size()) {
                String msg = messageTemplate
                        .replace("{key}", key)
                        .replace("{count}", String.valueOf(conds.size()));
                emit(new Alert(id, name, severity, msg, key, new ArrayList<>(st.evidence.values())));
                st.bits.clear();
                st.evidence.clear();
                st.firstTs = null;
            }
        }
    }
}
