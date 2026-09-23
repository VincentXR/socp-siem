package com.socp.rule;

import com.socp.rule.engine.AlertSink;
import com.socp.rule.engine.RuleDependencyException;
import com.socp.rule.engine.RuleEngine;
import com.socp.rule.engine.RuleProcessingObserver;
import com.socp.rule.engine.WatchlistStateStore;
import com.socp.rule.engine.Watchlists;
import com.socp.rule.model.Alert;
import com.socp.rule.model.SecurityEvent;
import com.socp.rule.model.Severity;
import com.socp.rule.rules.PatternRule;
import com.socp.rule.rules.ThresholdRule;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuleDependencyFailureTest {

    @AfterEach
    void resetWatchlists() {
        Watchlists.clear();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void repeatedDependencyFailuresNeverCompleteOrLoseStateEvenWhenWrapped(boolean wrapped) throws Exception {
        AtomicBoolean unavailable = new AtomicBoolean(true);
        AtomicInteger lookups = new AtomicInteger();
        AtomicInteger completed = new AtomicInteger();
        Watchlists.installStateStore(new WatchlistStateStore() {
            @Override public State find(String tenant, String name) {
                assertEquals("tenant-a", tenant);
                assertEquals("accounts", name);
                lookups.incrementAndGet();
                if (unavailable.get()) throw new IllegalArgumentException("external state unreadable");
                return new State(Set.of("alice"), false);
            }
            @Override public Set<String> names(String tenant) { throw new UnsupportedOperationException(); }
            @Override public State update(String tenant, String name, UnaryOperator<State> mutation) {
                throw new UnsupportedOperationException();
            }
            @Override public void clear() { }
        });

        ThresholdRule window = new ThresholdRule("WINDOW", "window", ignored -> true,
                SecurityEvent::host, 2, Duration.ofMinutes(5), Severity.HIGH, "threshold");
        PatternRule dependent = new PatternRule("DEPENDENT", "dependent", event -> {
            try {
                return Watchlists.contains(event.tenantId(), "accounts", "alice");
            } catch (RuleDependencyException failure) {
                if (wrapped) throw new IllegalArgumentException("adapter wrapper", failure);
                throw failure;
            }
        }, Severity.HIGH, "membership", "membership");
        CollectingSink sink = new CollectingSink();
        try (RuleEngine engine = new RuleEngine(List.of(window, dependent), List.of(sink))) {
            engine.start();
            SecurityEvent first = event("first");
            for (int attempt = 0; attempt < 10; attempt++) {
                ExecutionException failure = assertThrows(ExecutionException.class,
                        () -> engine.ingestAndAwait(first, completed::incrementAndGet).get(3, TimeUnit.SECONDS));
                assertTrue(RuleDependencyException.causedBy(failure));
                assertTrue(sink.alerts.isEmpty());
                assertEquals(0, completed.get(), "no durable completion during dependency outage");
                assertEquals("CLOSED", stats(engine, "DEPENDENT").get("ruleCircuit"));
            }
            assertEquals(10, lookups.get());
            assertEquals(10L, stats(engine, "DEPENDENT").get("ruleFailures"));
            assertEquals(0, stats(engine, "DEPENDENT").get("ruleConsecutiveFailures"));

            unavailable.set(false);
            engine.ingestAndAwait(first, completed::incrementAndGet).get(3, TimeUnit.SECONDS);
            assertEquals(1, sink.alerts.size(), "rolled-back window must not fire on its first event");
            assertEquals("DEPENDENT", sink.alerts.getFirst().ruleId());
            engine.ingestAndAwait(event("second"), completed::incrementAndGet).get(3, TimeUnit.SECONDS);
            assertEquals(2, completed.get());
            assertEquals(3, sink.alerts.size());
            Alert threshold = sink.alerts.stream().filter(alert -> "WINDOW".equals(alert.ruleId()))
                    .findFirst().orElseThrow();
            assertEquals(List.of("first", "second"),
                    threshold.evidence().stream().map(SecurityEvent::id).toList());
        }
    }

    @Test
    void timingObserverCannotIsolateAHealthyRule() throws Exception {
        AtomicInteger timings = new AtomicInteger();
        RuleProcessingObserver observer = new RuleProcessingObserver() {
            @Override public void ruleEvaluated(String ruleId, long nanos) {
                timings.incrementAndGet();
                throw new IllegalArgumentException("metrics backend rejected value");
            }
        };
        CollectingSink sink = new CollectingSink();
        PatternRule healthy = new PatternRule("HEALTHY", "healthy", ignored -> true,
                Severity.INFO, "healthy", "healthy");
        try (RuleEngine engine = new RuleEngine(List.of(healthy), List.of(sink), null, observer)) {
            engine.start();
            for (int index = 0; index < 5; index++) {
                engine.ingestAndAwait(event("event-" + index)).get(3, TimeUnit.SECONDS);
            }
            assertEquals(5, timings.get());
            assertEquals(5, sink.alerts.size());
            assertEquals("CLOSED", stats(engine, "HEALTHY").get("ruleCircuit"));
            assertEquals(0L, stats(engine, "HEALTHY").get("ruleFailures"));
        }
    }

    @Test
    void causeClassificationTerminatesForCyclicWrappers() {
        RuntimeException first = new RuntimeException("first");
        RuntimeException second = new RuntimeException("second", first);
        first.initCause(second);
        assertFalse(RuleDependencyException.causedBy(first));
        assertFalse(RuleDependencyException.causedBy(null));
        Throwable wrapped = new RuleDependencyException("unavailable", null);
        for (int depth = 0; depth < 100; depth++) wrapped = new IllegalArgumentException("adapter", wrapped);
        assertTrue(RuleDependencyException.causedBy(wrapped));
    }

    private static SecurityEvent event(String id) {
        return new SecurityEvent(id, Instant.parse("2026-09-21T00:00:00Z"), "auth", "host-a", "login",
                Map.of("tenant_id", "tenant-a"), Severity.INFO);
    }

    private static Map<String, Object> stats(RuleEngine engine, String ruleId) {
        return engine.ruleStats().stream().filter(item -> ruleId.equals(item.get("id")))
                .findFirst().orElseThrow();
    }

    private static final class CollectingSink implements AlertSink {
        private final List<Alert> alerts = new CopyOnWriteArrayList<>();
        @Override public void publish(Alert alert) { alerts.add(alert); }
        @Override public void close() { }
    }
}
