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
import java.util.BitSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;

/** Unordered multi-condition correlation rule. */
public final class CorrelationSetRule extends AbstractRule implements StatefulRule {

    private final Function<SecurityEvent, String> keyOf;
    private final List<Predicate<SecurityEvent>> conds;
    private final Duration window;
    private final Severity severity;
    private final String titleTemplate;
    private final String messageTemplate;

    private static final class State {
        final BitSet bits = new BitSet();
        Instant firstTs;
        final Map<Integer, SecurityEvent> evidence = new LinkedHashMap<>();
    }

    private final RuleStateMap<State> states = new RuleStateMap<>();

    public CorrelationSetRule(String id, String name,
                              Function<SecurityEvent, String> keyOf,
                              List<Predicate<SecurityEvent>> conds,
                              Duration window, Severity severity, String messageTemplate) {
        this(id, name, keyOf, conds, window, severity, name, messageTemplate);
    }

    public CorrelationSetRule(String id, String name,
                              Function<SecurityEvent, String> keyOf,
                              List<Predicate<SecurityEvent>> conds,
                              Duration window, Severity severity,
                              String titleTemplate, String messageTemplate) {
        super(id, name);
        if (conds.isEmpty()) throw new IllegalArgumentException("correlation-set requires a condition");
        this.keyOf = keyOf;
        this.conds = List.copyOf(conds);
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
                Map<String, Object> context = Map.of(
                        "key", key,
                        "count", conds.size(),
                        "window", window.toSeconds() + "s");
                String title = AlertTemplateRenderer.render(titleTemplate, event, context);
                String msg = AlertTemplateRenderer.render(messageTemplate, event, context);
                emit(new Alert(id, name, severity, title, msg, key,
                        new ArrayList<>(st.evidence.values())));
                st.bits.clear();
                st.evidence.clear();
                st.firstTs = null;
            }
        }
    }

    @Override
    public Map<String, Object> stats() {
        Map<String, Object> out = super.stats();
        out.putAll(states.stats());
        return out;
    }

    @Override
    public String stateVersion() {
        return "correlation-set-v1";
    }

    @Override
    public byte[] snapshotState() {
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        states.forEach((key, state) -> {
            synchronized (state) {
                Map<String, Object> value = new java.util.LinkedHashMap<>();
                value.put("bits", state.bits.toLongArray());
                value.put("firstTs", state.firstTs == null ? null : state.firstTs.toString());
                Map<String, Object> evidence = new java.util.LinkedHashMap<>();
                state.evidence.forEach((index, event) -> evidence.put(String.valueOf(index), StateSnapshotCodec.event(event)));
                value.put("evidence", evidence);
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
                state.bits.clear();
                Object bits = values.get("bits");
                if (bits instanceof List<?> numbers) {
                    for (int i = 0; i < numbers.size(); i++) {
                        if (numbers.get(i) instanceof Number n) {
                            long value = n.longValue();
                            for (int bit = 0; bit < Long.SIZE; bit++) {
                                if ((value & (1L << bit)) != 0) state.bits.set(i * Long.SIZE + bit);
                            }
                        }
                    }
                }
                state.firstTs = parseInstant(values.get("firstTs"));
                state.evidence.clear();
                Object evidence = values.get("evidence");
                if (evidence instanceof Map<?, ?> map) {
                    map.forEach((index, event) -> {
                        try {
                            state.evidence.put(Integer.parseInt(String.valueOf(index)), StateSnapshotCodec.event(event));
                        } catch (NumberFormatException ignored) {
                            // Ignore a corrupt individual evidence index; the state remains recoverable.
                        }
                    });
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
