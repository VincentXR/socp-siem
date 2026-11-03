package com.socp.rule.engine;

import com.socp.rule.model.Alert;
import com.socp.rule.model.SecurityEvent;
import com.socp.rule.model.Severity;
import com.socp.rule.rules.Rule;
import com.socp.rule.state.StatefulRule;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RuleEngineReloadTest {
    @Test void statelessEditRetainsLiveStateWithoutSerializationOrClosingSharedSinks() {
        var state = new State();
        var unusedState = new State();
        var oldPattern = new Stateless("pattern");
        var newPattern = new Stateless("pattern");
        var sink = new Sink();
        try (var live = engine(List.of(state, oldPattern), "definition", sink);
             var prepared = engine(List.of(unusedState, newPattern), "definition", sink)) {
            live.restore(List.of(event()));
            assertTrue(live.canReloadWithoutStateRecovery(prepared));
            live.reloadWithoutStateRecovery(prepared);
            assertEquals(1, state.events);
            assertEquals(0, state.closed);
            assertEquals(1, unusedState.closed);
            assertEquals(1, oldPattern.closed);
            assertEquals(0, newPattern.closed);
            assertEquals(0, sink.closed);
            live.restore(List.of(event()));
            assertEquals(2, state.events);
            assertEquals(1, newPattern.events);
            assertFalse(prepared.canReloadWithoutStateRecovery(live));
        }
        assertEquals(1, state.closed);
        assertEquals(1, newPattern.closed);
        assertEquals(1, sink.closed);
    }

    @Test void changedStatefulDefinitionRejectsReuseWithoutMutatingTheServingEngine() {
        var state = new State();
        try (var live = engine(List.of(state), "original-metadata", new Sink());
             var prepared = engine(List.of(new State()), "changed-metadata", new Sink())) {
            live.restore(List.of(event()));
            assertFalse(live.canReloadWithoutStateRecovery(prepared));
            assertThrows(IllegalArgumentException.class, () -> live.reloadWithoutStateRecovery(prepared));
            live.restore(List.of(event()));
            assertEquals(2, state.events);
            assertEquals(0, state.closed);
        }
    }

    @Test void removingStatefulRulesClosesThemWithoutReplayingUnchangedRules() {
        var state = new State();
        try (var live = engine(List.of(state), "same", new Sink());
             var prepared = engine(List.of(new Stateless("pattern")), "same", new Sink())) {
            assertTrue(live.canReloadWithoutStateRecovery(prepared));
            live.reloadWithoutStateRecovery(prepared);
            assertTrue(live.statefulRuleIds().isEmpty());
            assertEquals(1, state.closed);
        }
    }

    private static RuleEngine engine(List<Rule> rules, String definition, Sink sink) {
        return new RuleEngine(rules, List.of(sink), null, RuleProcessingObserver.NOOP,
                RuleExecutionScope.NOOP, null, Map.of("state", "state-v1"), Map.of(), Map.of("state", definition));
    }

    private static SecurityEvent event() {
        return new SecurityEvent(Instant.now(), "source", "host", "message", Map.of(), Severity.INFO);
    }

    private static class Stateless implements Rule {
        final String id;
        int events;
        int closed;
        Stateless(String id) { this.id = id; }
        public String id() { return id; }
        public String name() { return id; }
        public void accept(SecurityEvent event) { events++; }
        public List<Alert> drain() { return List.of(); }
        public void close() { closed++; }
    }

    private static final class State extends Stateless implements StatefulRule {
        State() { super("state"); }
        public String stateVersion() { return "state-v1"; }
        public byte[] snapshotState() { throw new AssertionError("unchanged state must not be copied"); }
        public void restoreState(byte[] bytes) { throw new AssertionError("unchanged state must not be replayed"); }
    }

    private static final class Sink implements AlertSink {
        int closed;
        public void publish(Alert alert) { }
        public void close() { closed++; }
    }
}
