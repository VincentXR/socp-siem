package com.socp.rule;

import com.socp.rule.model.SecurityEvent;
import com.socp.rule.model.Severity;
import com.socp.rule.rules.ThresholdRule;
import com.socp.rule.state.StateSnapshotCodec;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StatefulRuleSnapshotTest {

    @Test
    void thresholdStateRoundTripsAndPreservesWindow() {
        ThresholdRule original = threshold();
        original.accept(event("e-1", 0));
        byte[] snapshot = original.snapshotState();

        ThresholdRule restored = threshold();
        restored.restoreState(snapshot);
        restored.accept(event("e-2", 1));

        assertEquals(1, restored.drain().size());
        assertTrue(StateSnapshotCodec.read(snapshot).containsKey("host-1"));
    }

    @Test
    void ruleEngineRestoresOnlyCompatibleStatefulRules() {
        ThresholdRule rule = threshold();
        rule.accept(event("e-1", 0));
        try (var engine = new com.socp.rule.engine.RuleEngine(List.of(rule), List.of())) {
            var states = engine.snapshotStates();
            assertEquals(List.of("threshold"), engine.statefulRuleIds());

            ThresholdRule replacement = threshold();
            List<com.socp.rule.model.Alert> alerts = new java.util.ArrayList<>();
            com.socp.rule.engine.AlertSink sink = new com.socp.rule.engine.AlertSink() {
                @Override
                public void publish(com.socp.rule.model.Alert alert) {
                    alerts.add(alert);
                }

                @Override
                public void close() {
                }
            };
            try (var restored = new com.socp.rule.engine.RuleEngine(List.of(replacement),
                    List.of(sink))) {
                assertEquals(List.of("threshold"), restored.restoreStates(states));
                restored.start();
                restored.ingestAndAwait(event("e-2", 1)).join();
                assertEquals(1, alerts.size());
            }
        }
    }

    private static ThresholdRule threshold() {
        return new ThresholdRule("threshold", "Threshold", ignored -> true,
                SecurityEvent::host, 2, Duration.ofMinutes(5), Severity.HIGH, "count={count}");
    }

    private static SecurityEvent event(String id, long seconds) {
        return new SecurityEvent(id, Instant.ofEpochSecond(seconds), "auth", "host-1", id,
                Map.of("tenant_id", "tenant-a"), Severity.HIGH);
    }
}
