package com.socp.rule;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.socp.rule.engine.DetectionResult;
import com.socp.rule.engine.EventAlertSink;
import com.socp.rule.engine.RuleEngine;
import com.socp.rule.model.Alert;
import com.socp.rule.model.SecurityEvent;
import com.socp.rule.model.Severity;
import com.socp.rule.rules.ThresholdRule;
import com.socp.rule.state.StatefulRule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Bounded, opt-in evidence; no timing floor and no production-capacity claim. */
@EnabledIfSystemProperty(named = "socp.benchmark.serialization", matches = "true")
class RuleEngineSerializationBenchmarkTest {
    private static final Instant EVENT_TIME = Instant.parse("2026-01-01T00:00:00Z");
    private static final int WARMUP_EVENTS = 50;
    private static final int MEASURED_EVENTS = 100;

    @Test
    void measureDurableThresholdSerializationAtBoundedCardinalities() throws Exception {
        List<Map<String, Object>> scenarios = new ArrayList<>();
        for (int keys : new int[]{100, 1_000, 5_000}) {
            scenarios.add(measure(keys, false));
            scenarios.add(measure(keys, true));
        }
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("schemaVersion", 2);
        report.put("snapshotCacheMaxBytes", Long.getLong("socp.rule.snapshot-cache.max-bytes", 8L * 1024 * 1024));
        report.put("javaVersion", System.getProperty("java.version"));
        report.put("os", System.getProperty("os.name"));
        report.put("architecture", System.getProperty("os.arch"));
        report.put("availableProcessors", Runtime.getRuntime().availableProcessors());
        report.put("maxHeapBytes", Runtime.getRuntime().maxMemory());
        report.put("warmupEvents", WARMUP_EVENTS);
        report.put("measuredEvents", MEASURED_EVENTS);
        report.put("sink", "in-memory result verifier; excludes database, Kafka and checkpoint I/O");
        report.put("scenarios", scenarios);
        Path output = Path.of("target", "durable-state-serialization-benchmark.json");
        Files.createDirectories(output.getParent());
        new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(output.toFile(), report);
        System.out.println("Durable state serialization evidence: " + output);
    }

    private Map<String, Object> measure(int keys, boolean matching) throws Exception {
        ThresholdRule delegate = new ThresholdRule("benchmark-threshold", "benchmark",
                event -> "true".equals(event.get("match")), event -> event.get("entity"),
                10_000, Duration.ofMinutes(10), Severity.HIGH, "benchmark");
        for (int key = 0; key < keys; key++) delegate.accept(event("seed-" + key, key, true));
        assertEquals(keys, delegate.stats().get("stateKeys"));
        TimedStatefulRule rule = new TimedStatefulRule(delegate);
        VerifyingSink sink = new VerifyingSink(matching);
        long[] latencies = new long[MEASURED_EVENTS];
        long elapsed;
        long initialStateBytes;
        try (RuleEngine engine = new RuleEngine(List.of(rule), List.of(sink))) {
            engine.start();
            for (int i = 0; i < WARMUP_EVENTS; i++) {
                engine.ingestAndAwait(event("warmup-" + i, i % keys, matching))
                        .get(30, TimeUnit.SECONDS);
            }
            initialStateBytes = delegate.snapshotState().length;
            rule.resetMeasurements();
            sink.results = 0;
            long started = System.nanoTime();
            for (int i = 0; i < MEASURED_EVENTS; i++) {
                long eventStarted = System.nanoTime();
                engine.ingestAndAwait(event("measured-" + i, i % keys, matching))
                        .get(30, TimeUnit.SECONDS);
                latencies[i] = System.nanoTime() - eventStarted;
            }
            elapsed = System.nanoTime() - started;
        }
        // This count is a correctness guard for the measured implementation,
        // not a performance threshold. Change it deliberately if the contract
        // evolves, then compare like-for-like workload results.
        assertEquals(MEASURED_EVENTS * 2L, rule.snapshotCalls);
        assertEquals(MEASURED_EVENTS, sink.results);
        assertEquals(MEASURED_EVENTS, rule.acceptCalls);
        assertEquals(keys, delegate.stats().get("stateKeys"));
        assertTrue(rule.snapshotBytes > 0);
        Arrays.sort(latencies);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("stateKeys", keys);
        result.put("matching", matching);
        result.put("initialStateBytes", initialStateBytes);
        result.put("finalStateBytes", delegate.snapshotState().length);
        result.put("elapsedNanos", elapsed);
        result.put("snapshotCalls", rule.snapshotCalls);
        result.put("serializedBytes", rule.snapshotBytes);
        result.put("snapshotNanos", rule.snapshotNanos);
        result.put("acceptNanos", rule.acceptNanos);
        result.put("eventsPerSecond", MEASURED_EVENTS * 1_000_000_000.0 / elapsed);
        result.put("snapshotElapsedFraction", (double) rule.snapshotNanos / elapsed);
        result.put("p50Nanos", latencies[49]);
        result.put("p95Nanos", latencies[94]);
        result.put("p99Nanos", latencies[98]);
        return result;
    }

    private SecurityEvent event(String id, int key, boolean matching) {
        return new SecurityEvent(id, EVENT_TIME, "auth", "benchmark-host", "benchmark-event",
                Map.of("tenant_id", "benchmark", "entity", "key-" + key,
                        "match", Boolean.toString(matching)), Severity.INFO);
    }

    private static final class TimedStatefulRule implements StatefulRule {
        private final StatefulRule delegate;
        private long snapshotCalls;
        private long snapshotBytes;
        private long snapshotNanos;
        private long acceptCalls;
        private long acceptNanos;

        private TimedStatefulRule(StatefulRule delegate) { this.delegate = delegate; }
        @Override public String id() { return delegate.id(); }
        @Override public String name() { return delegate.name(); }
        @Override public String stateVersion() { return delegate.stateVersion(); }
        @Override public List<Alert> drain() { return delegate.drain(); }
        @Override public void restoreState(byte[] bytes) { delegate.restoreState(bytes); }
        @Override public void close() { delegate.close(); }
        @Override public void accept(SecurityEvent event) {
            long started = System.nanoTime();
            delegate.accept(event);
            acceptNanos += System.nanoTime() - started;
            acceptCalls++;
        }
        @Override public byte[] snapshotState() {
            long started = System.nanoTime();
            byte[] result = delegate.snapshotState();
            snapshotNanos += System.nanoTime() - started;
            snapshotCalls++;
            snapshotBytes += result.length;
            return result;
        }
        private void resetMeasurements() {
            snapshotCalls = 0;
            snapshotBytes = 0;
            snapshotNanos = 0;
            acceptCalls = 0;
            acceptNanos = 0;
        }
    }

    private static final class VerifyingSink implements EventAlertSink {
        private final boolean matching;
        private int results;
        private VerifyingSink(boolean matching) { this.matching = matching; }
        @Override public void publish(SecurityEvent event, List<Alert> alerts) {
            throw new AssertionError("the result-aware durable path must be measured");
        }
        @Override public void publish(DetectionResult result, Runnable guard) {
            assertTrue(result.alerts().isEmpty());
            assertEquals(1, result.stateChanges().size());
            assertEquals(matching, result.stateChanges().getFirst().changed());
            assertFalse(result.stateChanges().getFirst().beforeDigest().isBlank());
            assertFalse(result.stateChanges().getFirst().afterDigest().isBlank());
            results++;
        }
        @Override public void close() { }
    }
}
