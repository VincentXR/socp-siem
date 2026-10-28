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
    private static final Object PINNING_CONTROL = new Object();

    @Test
    void blockingRuleDependenciesDoNotPinTheVirtualWorker(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path temporary) throws Exception {
        var recordingPath = temporary.resolve("rule-worker.jfr");
        try (var recording = new jdk.jfr.Recording()) {
            recording.enable("jdk.VirtualThreadPinned").withStackTrace()
                    .withThreshold(java.time.Duration.ZERO);
            recording.start();
            // Positive control: prove this runtime reports monitor pinning.
            Thread.ofVirtual().name("pinning-control").start(() -> {
                synchronized (PINNING_CONTROL) {
                    java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(150));
                }
            }).join();
            Rule rule = new PatternRule("BLOCKING-DEPENDENCY", "blocking", event -> {
                java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(150));
                return false;
            }, Severity.HIGH, "dependency read");
            try (RuleEngine engine = new RuleEngine(List.of(rule), List.of(), null)) {
                engine.start();
                engine.ingestAndAwait(new SecurityEvent("blocking-event", Instant.now(),
                        "auth", "host", "raw", Map.of("tenant_id", "timing-tenant"), Severity.HIGH))
                        .get(10, TimeUnit.SECONDS);
            }
            recording.stop();
            recording.dump(recordingPath);
        }
        var pins = jdk.jfr.consumer.RecordingFile.readAllEvents(recordingPath);
        assertTrue(pins.stream().anyMatch(event -> "pinning-control".equals(event.getThread().getJavaName())));
        assertFalse(pins.stream().anyMatch(event -> event.getStackTrace() != null
                && event.getStackTrace().getFrames().stream().anyMatch(frame ->
                RuleEngine.class.getName().equals(frame.getMethod().getType().getName()))),
                "Blocking durable evaluation must release the Java 21 carrier");
    }

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
