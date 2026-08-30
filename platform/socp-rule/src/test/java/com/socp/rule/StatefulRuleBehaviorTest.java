package com.socp.rule;

import com.socp.rule.model.Alert;
import com.socp.rule.model.SecurityEvent;
import com.socp.rule.model.Severity;
import com.socp.rule.rules.BaselineRule;
import com.socp.rule.rules.CorrelationRule;
import com.socp.rule.rules.CorrelationSetRule;
import com.socp.rule.rules.RareValueRule;
import com.socp.rule.rules.ThresholdRule;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Behaviour and recovery coverage for the stateful detection primitives. */
class StatefulRuleBehaviorTest {

    @Test
    void baselineLearnsWindowsThenAlertsOnceAndRoundTrips() {
        BaselineRule rule = new BaselineRule("baseline", "Baseline", ignored -> true,
                SecurityEvent::host, Duration.ofMinutes(1), 3, 2, 1.0, 1,
                Severity.HIGH, "{key} count={count} baseline={baseline}");
        rule.accept(event("b-0", 0, "baseline"));
        rule.accept(event("b-1", 60, "baseline"));
        rule.accept(event("b-2", 120, "baseline"));
        assertEquals(1, rule.snapshot().size());
        rule.accept(event("b-3", 120, "baseline"));
        rule.accept(event("b-4", 120, "baseline"));
        rule.accept(event("b-5", 120, "baseline"));
        List<Alert> alerts = rule.drain();
        assertEquals(1, alerts.size());
        assertTrue(alerts.getFirst().message().contains("baseline=1.0"));
        rule.accept(event("b-6", 120, "baseline"));
        assertTrue(rule.drain().isEmpty(), "a bucket must alert only once");

        BaselineRule restored = new BaselineRule("baseline", "Baseline", ignored -> true,
                SecurityEvent::host, Duration.ofMinutes(1), 3, 2, 1.0, 1,
                Severity.HIGH, "{key} count={count}");
        restored.restoreState(rule.snapshotState());
        assertNotNull(restored.stats().get("stateKeys"));
        assertThrows(IllegalArgumentException.class,
                () -> restored.restoreState(Map.of("invalid", "ignored").toString().getBytes()));
    }

    @Test
    void rareValueLearnsKnownValuesAndAlertsForNewValue() {
        RareValueRule rule = new RareValueRule("rare", "Rare", ignored -> true,
                SecurityEvent::host, event -> event.get("value"), "value", 2,
                Severity.MEDIUM, "{key} {field}={value} known={known}");
        rule.accept(event("r-1", 0, "h", "a"));
        rule.accept(event("r-2", 1, "h", "a"));
        rule.accept(event("r-3", 2, "h", "b"));
        assertEquals(1, rule.drain().size());
        assertEquals(2, rule.snapshot().getFirst().get("known"));

        RareValueRule restored = new RareValueRule("rare", "Rare", ignored -> true,
                SecurityEvent::host, event -> event.get("value"), "value", 2,
                Severity.MEDIUM, "{value}");
        restored.restoreState(rule.snapshotState());
        restored.accept(event("r-4", 3, "h", "c"));
        assertEquals(1, restored.drain().size());
        restored.restoreState("{}".getBytes());
    }

    @Test
    void orderedCorrelationRestartsFromFirstStepAndExpires() {
        CorrelationRule rule = new CorrelationRule("corr", "Correlation", SecurityEvent::host,
                List.of(e -> "failed".equals(e.get("phase")), e -> "accepted".equals(e.get("phase"))),
                Duration.ofMinutes(5), Severity.HIGH, "{key} {count}");
        rule.accept(event("c-1", 0, "h", "other"));
        rule.accept(event("c-2", 0, "h", "failed"));
        rule.accept(event("c-3", 360, "h", "accepted"));
        assertTrue(rule.drain().isEmpty(), "events outside the correlation window must expire");
        rule.accept(event("c-4", 400, "h", "failed"));
        rule.accept(event("c-5", 401, "h", "other"));
        rule.accept(event("c-6", 402, "h", "accepted"));
        List<Alert> alerts = rule.drain();
        assertEquals(1, alerts.size());
        assertEquals(2, alerts.getFirst().evidence().size());

        CorrelationRule restored = new CorrelationRule("corr", "Correlation", SecurityEvent::host,
                List.of(e -> "failed".equals(e.get("phase")), e -> "accepted".equals(e.get("phase"))),
                Duration.ofMinutes(5), Severity.HIGH, "{key}");
        restored.restoreState(rule.snapshotState());
        restored.restoreState("{\"h\":{\"step\":1,\"firstTs\":\"bad\",\"lastTs\":null,\"evidence\":[]}}".getBytes());
        assertFalse(restored.stats().isEmpty());
    }

    @Test
    void unorderedCorrelationCompletesInAnyOrderAndResetsAfterWindow() {
        CorrelationSetRule rule = new CorrelationSetRule("set", "Set", SecurityEvent::host,
                List.of(e -> "accepted".equals(e.get("phase")), e -> "failed".equals(e.get("phase"))),
                Duration.ofMinutes(5), Severity.HIGH, "{key} {count}");
        rule.accept(event("s-1", 0, "h", "accepted"));
        rule.accept(event("s-2", 1, "h", "failed"));
        assertEquals(1, rule.drain().size());
        rule.accept(event("s-3", 2, "h", "accepted"));
        rule.accept(event("s-4", 400, "h", "failed"));
        assertTrue(rule.drain().isEmpty(), "expired partial correlation must not alert");
        assertTrue(rule.snapshotState().length > 0);
        rule.restoreState("{\"h\":{\"bits\":[1],\"firstTs\":\"bad\",\"evidence\":{\"bad\":{}}}}".getBytes());
    }

    @Test
    void thresholdIgnoresNonMatchingAndMissingKeys() {
        ThresholdRule rule = new ThresholdRule("threshold", "Threshold", e -> "yes".equals(e.get("match")),
                e -> e.get("key"), 2, Duration.ofMinutes(5), Severity.HIGH, "{key}={count}");
        rule.accept(event("t-1", 0, "h", "ignored"));
        rule.accept(event("t-2", 1, "h", "yes"));
        rule.accept(new SecurityEvent("t-3", Instant.ofEpochSecond(2), "auth", "h", "raw",
                Map.of("match", "yes"), Severity.INFO));
        assertTrue(rule.drain().isEmpty());
        assertTrue(rule.stats().containsKey("stateKeys"));
    }

    private static SecurityEvent event(String id, long seconds, String host) {
        return event(id, seconds, host, "value");
    }

    private static SecurityEvent event(String id, long seconds, String host, String phase) {
        return new SecurityEvent(id, Instant.ofEpochSecond(seconds), "auth", host, id,
                Map.of("tenant_id", "tenant-a", "phase", phase, "value", phase, "key", host,
                        "match", phase), Severity.INFO);
    }
}
