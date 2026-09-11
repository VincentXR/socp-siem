package com.socp.rule.engine;

import com.socp.rule.model.Alert;
import com.socp.rule.model.SecurityEvent;
import com.socp.rule.model.Severity;
import com.socp.rule.rules.Rule;
import com.socp.rule.state.StatefulRule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Single-worker stateful rule engine.
 *
 * <p>The legacy {@link #ingest(SecurityEvent)} method keeps its non-blocking
 * admission contract. Kafka uses {@link #ingestAndAwait(SecurityEvent)} so the
 * caller receives a completion signal only after every durable sink has
 * returned. This distinction is what lets the transport commit offset lag
 * behind business processing without serialising the Kafka poll loop.</p>
 */
public final class RuleEngine implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(RuleEngine.class);
    private static final SecurityEvent POISON_EVENT = new SecurityEvent(
            Instant.EPOCH, "POISON", "POISON", "POISON", Map.of(), Severity.INFO);

    private final AtomicReference<List<Rule>> rulesRef;
    private final List<AlertSink> sinks;
    private final Suppressor suppressor;
    private final RuleProcessingObserver observer;
    private final RuleExecutionScope executionScope;
    private final BlockingQueue<WorkItem> queue = new ArrayBlockingQueue<>(100_000);
    private final ReentrantReadWriteLock lifecycle = new ReentrantReadWriteLock();
    private volatile boolean running = true;
    private Thread worker;

    private final AtomicLong eventCount = new AtomicLong();
    private final AtomicLong alertCount = new AtomicLong();
    private final AtomicLong dropCount = new AtomicLong();
    /** Serializes rule mutation, durable position callbacks, and snapshots. */
    private final Object stateLock = new Object();

    /** Immediate queue admission plus an optional durable completion signal. */
    public record Submission(boolean accepted, CompletableFuture<Void> completion) {
    }

    private record WorkItem(SecurityEvent event, CompletableFuture<Void> completion,
                            boolean durable, Runnable onDurable) {
    }

    public RuleEngine(List<Rule> rules, List<AlertSink> sinks) {
        this(rules, sinks, null, RuleProcessingObserver.NOOP);
    }

    public RuleEngine(List<Rule> rules, List<AlertSink> sinks, Suppressor suppressor) {
        this(rules, sinks, suppressor, RuleProcessingObserver.NOOP);
    }

    public RuleEngine(List<Rule> rules, List<AlertSink> sinks, Suppressor suppressor,
                      RuleProcessingObserver observer) {
        this(rules, sinks, suppressor, observer, RuleExecutionScope.NOOP);
    }

    public RuleEngine(List<Rule> rules, List<AlertSink> sinks, Suppressor suppressor,
                      RuleProcessingObserver observer, RuleExecutionScope executionScope) {
        this.rulesRef = new AtomicReference<>(List.copyOf(rules));
        this.sinks = List.copyOf(sinks);
        this.suppressor = suppressor;
        this.observer = observer == null ? RuleProcessingObserver.NOOP : observer;
        this.executionScope = executionScope == null ? RuleExecutionScope.NOOP : executionScope;
    }

    public void start() {
        lifecycle.writeLock().lock();
        try {
            if (!running) throw new IllegalStateException("detection engine is closed");
            if (worker != null && worker.isAlive()) return;
            worker = Thread.startVirtualThread(this::loop);
        } finally {
            lifecycle.writeLock().unlock();
        }
    }

    /**
     * Rebuild stateful rule windows without replaying historical alerts.
     * Historical rows supplied here are completed durable events only.
     */
    public void restore(List<SecurityEvent> history) {
        if (history == null || history.isEmpty()) return;
        synchronized (stateLock) {
            for (SecurityEvent event : history) {
                for (Rule rule : rulesRef.get()) rule.accept(event);
                for (Rule rule : rulesRef.get()) rule.drain();
            }
        }
        log.info("Detection rule state restored events={}", history.size());
    }

    private void loop() {
        while (true) {
            try {
                WorkItem item = queue.take();
                if (item.event() == POISON_EVENT) break;
                try {
                    process(item);
                    if (item.completion() != null) item.completion().complete(null);
                } catch (Throwable ex) {
                    notifyFailure(item.event(), ex);
                    if (item.completion() != null) {
                        item.completion().completeExceptionally(ex);
                    } else {
                        log.error("Alert processing failed eventId={}: {}",
                                item.event().id(), ex.getMessage(), ex);
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    private void process(WorkItem item) {
        synchronized (stateLock) {
            SecurityEvent event = item.event();
            try (RuleExecutionScope.Scope ignored = executionScope.open(event)) {
                processInScope(item, event);
            }
            // Position bookkeeping is deliberately inside the same critical
            // section as state mutation. A checkpoint can therefore never
            // capture rule bytes ahead of the Kafka watermark it records.
            if (item.onDurable() != null) item.onDurable().run();
        }
    }

    private void processInScope(WorkItem item, SecurityEvent event) {
        eventCount.incrementAndGet();
        List<Rule> rules = rulesRef.get();
        for (Rule rule : rules) rule.accept(event);

        List<Alert> candidates = new ArrayList<>();
        for (Rule rule : rules) candidates.addAll(rule.drain());
        try (Suppressor.Batch batch = suppressor == null ? null : suppressor.begin(candidates)) {
            List<Alert> emitted = batch == null ? List.copyOf(candidates) : batch.alerts();
            notifyEvaluationCompleted(event, emitted.size());
            boolean delivered = true;
            for (AlertSink sink : sinks) {
                try {
                    if (sink instanceof EventAlertSink eventSink) {
                        eventSink.publish(event, emitted);
                    } else {
                        for (Alert alert : emitted) sink.publish(alert);
                    }
                } catch (RuntimeException ex) {
                    delivered = false;
                    if (item.durable()) throw ex;
                    log.error("Alert sink failed eventId={} alerts={}: {}",
                            event.id(), emitted.size(), ex.getMessage(), ex);
                }
            }
            if (delivered) {
                if (batch != null) batch.commit();
                alertCount.addAndGet(emitted.size());
                notifyDurableSinksCompleted(event, emitted.size());
            }
        }
    }

    private void notifyEvaluationCompleted(SecurityEvent event, int emittedAlerts) {
        try {
            observer.evaluationCompleted(event, emittedAlerts);
        } catch (RuntimeException metricsFailure) {
            log.debug("Rule processing observer failed at evaluation boundary: {}", metricsFailure.getMessage());
        }
    }

    private void notifyDurableSinksCompleted(SecurityEvent event, int emittedAlerts) {
        try {
            observer.durableSinksCompleted(event, emittedAlerts);
        } catch (RuntimeException metricsFailure) {
            log.debug("Rule processing observer failed at durable boundary: {}", metricsFailure.getMessage());
        }
    }

    private void notifyFailure(SecurityEvent event, Throwable failure) {
        try {
            observer.processingFailed(event, failure);
        } catch (RuntimeException metricsFailure) {
            log.debug("Rule processing observer failed at failure boundary: {}", metricsFailure.getMessage());
        }
    }

    /** Legacy non-blocking ingestion API. */
    public boolean ingest(SecurityEvent event) {
        return submit(event, false, null).accepted();
    }

    /** Submit an event and complete after durable sinks have finished. */
    public CompletableFuture<Void> ingestAndAwait(SecurityEvent event) {
        return ingestAndAwait(event, null);
    }

    /** Submit an event and run the callback before the completion signal. */
    public CompletableFuture<Void> ingestAndAwait(SecurityEvent event, Runnable onDurable) {
        return submit(event, true, onDurable).completion();
    }

    public Submission submit(SecurityEvent event, boolean durable) {
        return submit(event, durable, null);
    }

    public Submission submit(SecurityEvent event, boolean durable, Runnable onDurable) {
        CompletableFuture<Void> completion = new CompletableFuture<>();
        WorkItem item = new WorkItem(event, completion, durable, onDurable);
        lifecycle.readLock().lock();
        try {
            if (!running) {
                completion.completeExceptionally(new IllegalStateException("detection engine is closed"));
                return new Submission(false, completion);
            }
            if (queue.offer(item)) return new Submission(true, completion);
            try {
                if (queue.offer(item, 50, TimeUnit.MILLISECONDS)) {
                    return new Submission(true, completion);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        } finally {
            lifecycle.readLock().unlock();
        }
        dropCount.incrementAndGet();
        completion.completeExceptionally(new IllegalStateException("detection queue full"));
        return new Submission(false, completion);
    }

    public double queueLoad() {
        int cap = queue.size() + queue.remainingCapacity();
        return cap == 0 ? 0.0 : (double) queue.size() / cap;
    }

    public void close() {
        boolean interrupted = false;
        lifecycle.writeLock().lock();
        try {
            if (!running) return;
            // Admission is now closed. Every item already in the queue is
            // before the poison marker and therefore receives its completion
            // signal before the worker exits.
            running = false;
            for (;;) {
                try {
                    queue.put(new WorkItem(POISON_EVENT, null, false, null));
                    break;
                } catch (InterruptedException interruption) {
                    // Closing without the marker can strand every accepted
                    // completion forever. Preserve the signal only after the
                    // marker is durably ordered behind accepted work.
                    interrupted = true;
                }
            }
        } finally {
            lifecycle.writeLock().unlock();
        }
        if (worker != null) {
            while (worker.isAlive()) {
                try {
                    worker.join();
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
        }
        rulesRef.get().forEach(Rule::close);
        sinks.forEach(AlertSink::close);
        if (interrupted) Thread.currentThread().interrupt();
    }

    public void reload(List<Rule> newRules) {
        if (newRules == null) throw new IllegalArgumentException("rules are required");
        List<Rule> replacement = List.copyOf(newRules);
        synchronized (stateLock) {
            List<Rule> old = rulesRef.getAndSet(replacement);
            old.forEach(Rule::close);
            log.info("Detection rules reloaded count={}", replacement.size());
        }
    }

    public List<Map<String, Object>> ruleStats() {
        return rulesRef.get().stream().map(Rule::stats).toList();
    }

    /** Portable state checkpoint payloads keyed by rule id. */
    public Map<String, RuleState> snapshotStates() {
        return captureStateSnapshot(java.util.function.Function.identity());
    }

    /** Capture state and its durable progress in the same critical section. */
    public <T> T captureStateSnapshot(java.util.function.Function<Map<String, RuleState>, T> capture) {
        synchronized (stateLock) {
            Map<String, RuleState> out = new java.util.LinkedHashMap<>();
            for (Rule rule : rulesRef.get()) {
                if (!(rule instanceof StatefulRule stateful)) continue;
                out.put(rule.id(), new RuleState(rule.id(), stateful.stateVersion(), stateful.snapshotState()));
            }
            return capture.apply(Map.copyOf(out));
        }
    }

    public List<String> statefulRuleIds() {
        return rulesRef.get().stream()
                .filter(StatefulRule.class::isInstance)
                .map(Rule::id)
                .toList();
    }

    /** Restore compatible rule state before journal replay. Incompatible bytes are ignored. */
    public List<String> restoreStates(Map<String, RuleState> states) {
        if (states == null || states.isEmpty()) return List.of();
        synchronized (stateLock) {
            Map<StatefulRule, byte[]> before = new java.util.LinkedHashMap<>();
            for (Rule rule : rulesRef.get()) {
                if (rule instanceof StatefulRule stateful) {
                    before.put(stateful, stateful.snapshotState());
                }
            }
            List<String> restored = new ArrayList<>();
            for (Rule rule : rulesRef.get()) {
                if (!(rule instanceof StatefulRule stateful)) continue;
                RuleState snapshot = states.get(rule.id());
                if (snapshot == null || !stateful.stateVersion().equals(snapshot.version())) continue;
                try {
                    stateful.restoreState(snapshot.serializedState());
                    restored.add(rule.id());
                } catch (RuntimeException failure) {
                    // A partially restored engine is more dangerous than a cold
                    // replay: the next tail would double-apply the rules that did
                    // restore successfully. Roll every stateful rule back to the
                    // bytes captured before this attempt and let the caller choose
                    // a complete journal replay instead.
                    before.forEach((candidate, original) -> {
                        try {
                            candidate.restoreState(original);
                        } catch (RuntimeException rollbackFailure) {
                            log.warn("Unable to roll back state snapshot ruleId={}: {}",
                                    candidate.id(), rollbackFailure.getMessage());
                        }
                    });
                    log.warn("Ignoring incomplete state snapshot ruleId={}: {}",
                            rule.id(), failure.getMessage());
                    return List.of();
                }
            }
            return List.copyOf(restored);
        }
    }

    public record RuleState(String ruleId, String version, byte[] serializedState) {
        public RuleState {
            if (ruleId == null || ruleId.isBlank()) throw new IllegalArgumentException("ruleId is required");
            if (version == null || version.isBlank()) throw new IllegalArgumentException("version is required");
            serializedState = serializedState == null ? new byte[0] : serializedState.clone();
        }

        @Override
        public byte[] serializedState() {
            return serializedState.clone();
        }
    }

    public long eventCount() {
        return eventCount.get();
    }

    public long alertCount() {
        return alertCount.get();
    }

    public long dropCount() {
        return dropCount.get();
    }

    public long suppressedCount() {
        return suppressor == null ? 0 : suppressor.suppressed();
    }
}
