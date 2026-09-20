package com.socp.rule;

import com.socp.rule.engine.AlertSink;
import com.socp.rule.engine.RuleEngine;
import com.socp.rule.engine.RuleProcessingObserver;
import com.socp.rule.model.Alert;
import com.socp.rule.model.SecurityEvent;
import com.socp.rule.model.Severity;
import com.socp.rule.rules.PatternRule;
import com.socp.rule.rules.Rule;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The engine reports per-rule evaluation latency through the processing
 * observer, which is the runtime regression signal behind the ReDoS/complexity
 * budget (review 2026-09, security): a rule that clears static validation but
 * still hogs the worker thread becomes an observable slow-rule series.
 */
class RuleEngineEvaluationTimingTest {

    @Test
    void reportsPerRuleEvaluationTimingToObserver() throws Exception {
        List<String> observed = new CopyOnWriteArrayList<>();
        List<Long> durations = new CopyOnWriteArrayList<>();
        RuleProcessingObserver observer = new RuleProcessingObserver() {
            @Override
            public void ruleEvaluated(String ruleId, long nanos) {
                observed.add(ruleId);
                durations.add(nanos);
            }
        };
        Rule rule = new PatternRule("TIMING-RULE", "timing", event -> true,
                Severity.HIGH, "always matches");
        AlertSink noop = new AlertSink() {
            @Override public void publish(Alert alert) { }
            @Override public void close() { }
        };

        try (RuleEngine engine = new RuleEngine(List.of(rule), List.of(noop), null, observer)) {
            engine.start();
            Map<String, String> fields = new HashMap<>();
            fields.put("tenant_id", "timing-tenant");
            SecurityEvent event = new SecurityEvent("timing-event", Instant.now(),
                    "auth", "host-1", "raw", fields, Severity.HIGH);
            engine.ingestAndAwait(event).get(10, TimeUnit.SECONDS);
        }

        assertTrue(observed.contains("TIMING-RULE"),
                "observed rules = " + observed);
        assertFalse(durations.isEmpty());
        assertTrue(durations.stream().allMatch(nanos -> nanos >= 0L),
                "evaluation timing must be a non-negative duration");
    }
}
