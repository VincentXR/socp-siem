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
    private final Runnable durableCommitGuard;
    private final Map<String, String> stateCompatibilityVersions;
    private final BlockingQueue<WorkItem> queue = new ArrayBlockingQueue<>(100_000);
    private final Map<String, RuleCircuitState> ruleCircuits = new java.util.concurrent.ConcurrentHashMap<>();
    private final ReentrantReadWriteLock lifecycle = new ReentrantReadWriteLock();
    private volatile boolean running = true;
    private Thread worker;

    private final AtomicLong eventCount = new AtomicLong();
    private final AtomicLong alertCount = new AtomicLong();
    private final AtomicLong dropCount = new AtomicLong();
    /** Serializes rule mutation, durable position callbacks, and snapshots. */
    private final Object stateLock = new Object();
    private static final int RULE_FAILURE_THRESHOLD = Math.max(1,
            Integer.getInteger("socp.rule.failure-threshold", 3));
    private static final long RULE_FUSE_COOLDOWN_NANOS = TimeUnit.SECONDS.toNanos(
            Math.max(1L, Long.getLong("socp.rule.fuse-cooldown-seconds", 60L)));

    /** Immediate queue admission plus an optional durable completion signal. */
    public record Submission(boolean accepted, CompletableFuture<Void> completion) {
    }

    private record WorkItem(SecurityEvent event, CompletableFuture<Void> completion,
                            boolean durable, Runnable onDurable, Runnable durableCommitGuard,
                            DetectionResult.InputPosition inputPosition) {
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
        this(rules, sinks, suppressor, observer, executionScope, null);
    }

    /**
     * Create an engine with a guard that is evaluated around the durable sink
     * boundary. The Detection service uses this hook for a database fencing
     * token while the shared rule engine remains storage-agnostic.
     */
    public RuleEngine(List<Rule> rules, List<AlertSink> sinks, Suppressor suppressor,
                      RuleProcessingObserver observer, RuleExecutionScope executionScope,
                      Runnable durableCommitGuard) {
        this(rules, sinks, suppressor, observer, executionScope, durableCommitGuard, Map.of());
    }

    /**
     * Create an engine with explicit per-rule state compatibility versions.
     * Detection supplies a semantic fingerprint so configuration changes do
     * not reuse a state window merely because the serialized format is stable.
     */
    public RuleEngine(List<Rule> rules, List<AlertSink> sinks, Suppressor suppressor,
                      RuleProcessingObserver observer, RuleExecutionScope executionScope,
                      Runnable durableCommitGuard, Map<String, String> stateCompatibilityVersions) {
        this.rulesRef = new AtomicReference<>(List.copyOf(rules));
        this.sinks = List.copyOf(sinks);
        this.suppressor = suppressor;
        this.observer = observer == null ? RuleProcessingObserver.NOOP : observer;
        this.executionScope = executionScope == null ? RuleExecutionScope.NOOP : executionScope;
        this.durableCommitGuard = durableCommitGuard;
        this.stateCompatibilityVersions = stateCompatibilityVersions == null
                ? Map.of() : Map.copyOf(stateCompatibilityVersions);
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
            Map<StatefulRule, byte[]> before = item.durable() ? snapshotMutableStates() : Map.of();
            try {
                try (RuleExecutionScope.Scope ignored = executionScope.open(event)) {
                    processInScope(item, event, before);
                }
                // Position bookkeeping is deliberately inside the same
                // critical section as state mutation. A checkpoint can
                // therefore never capture rule bytes ahead of its watermark.
                if (item.onDurable() != null) item.onDurable().run();
            } catch (RuntimeException | Error failure) {
                if (item.durable() && !before.isEmpty()) {
                    try {
                        restoreMutableStates(before);
                    } catch (RuntimeException rollbackFailure) {
                        failure.addSuppressed(rollbackFailure);
                        log.error("Unable to roll back detection state eventId={}: {}",
                                event.id(), rollbackFailure.getMessage(), rollbackFailure);
                    }
                }
                throw failure;
            }
        }
    }

    private void processInScope(WorkItem item, SecurityEvent event,
                                Map<StatefulRule, byte[]> before) {
        eventCount.incrementAndGet();
        List<Rule> rules = rulesRef.get();
        for (Rule rule : rules) {
            if (ruleCircuitOpen(rule)) {
                log.warn("Skipping isolated detection rule ruleId={} eventId={}", rule.id(), event.id());
                continue;
            }
            acceptRule(rule, event);
        }

        List<Alert> candidates = new ArrayList<>();
        for (Rule rule : rules) candidates.addAll(rule.drain());
        try (Suppressor.Batch batch = suppressor == null ? null : suppressor.begin(candidates)) {
            List<Alert> emitted = batch == null ? List.copyOf(candidates) : batch.alerts();
            notifyEvaluationCompleted(event, emitted.size());
            DetectionResult result = new DetectionResult(
                    event,
                    item.inputPosition(),
                    ruleVersions(rules),
                    stateChanges(before, rules),
                    candidates,
                    emitted,
                    batch == null
                            ? DetectionResult.SuppressionDecision.none(candidates, emitted)
                            : DetectionResult.SuppressionDecision.window(candidates, emitted),
                    event.scopedId());
            boolean delivered = true;
            boolean eventAwareBoundary = false;
            runCommitGuard(item);
            for (AlertSink sink : sinks) {
                try {
                    if (sink instanceof EventAlertSink eventSink) {
                        eventAwareBoundary = true;
                        eventSink.publish(result, commitGuard(item));
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
                // Event-aware sinks own the transaction that contains the
                // second fence check and journal completion. Legacy sinks do
                // not have that boundary, so retain the post-sink check for
                // them before committing suppression state.
                if (!eventAwareBoundary) runCommitGuard(item);
                if (batch != null) batch.commit();
                alertCount.addAndGet(emitted.size());
                notifyDurableSinksCompleted(event, emitted.size());
            }
        }
    }

    /**
     * Evaluate one rule with its own rollback boundary. A permanently bad
     * rule must not prevent healthy rules from producing a durable result for
     * the same event; transient failures still escape to the caller so the
     * event remains retryable.
     */
    private void acceptRule(Rule rule, SecurityEvent event) {
        byte[] before = rule instanceof StatefulRule stateful ? stateful.snapshotState() : null;
        try {
            rule.accept(event);
            ruleCircuits.computeIfAbsent(rule.id(), ignored -> new RuleCircuitState()).success();
        } catch (RuntimeException failure) {
            if (before != null && rule instanceof StatefulRule stateful) {
                try {
                    stateful.restoreState(before);
                } catch (RuntimeException rollbackFailure) {
                    failure.addSuppressed(rollbackFailure);
                    throw failure;
                }
            }
            // A failed rule may have emitted a candidate before throwing. It
            // must not leak that candidate into the next event.
            rule.drain();
            RuleCircuitState circuit = ruleCircuits.computeIfAbsent(
                    rule.id(), ignored -> new RuleCircuitState());
            boolean opened = circuit.failure(failure,
                    failure instanceof IllegalArgumentException);
            if (opened) {
                log.error("Detection rule isolated ruleId={} eventId={} failures={} reason={}",
                        rule.id(), event.id(), circuit.failures(), failure.getMessage());
                return;
            }
            throw failure;
        }
    }

    private boolean ruleCircuitOpen(Rule rule) {
        RuleCircuitState circuit = ruleCircuits.get(rule.id());
        return circuit != null && circuit.open();
    }

    private Map<StatefulRule, byte[]> snapshotMutableStates() {
        Map<StatefulRule, byte[]> before = new java.util.LinkedHashMap<>();
        for (Rule rule : rulesRef.get()) {
            if (rule instanceof StatefulRule stateful) before.put(stateful, stateful.snapshotState());
        }
        return before;
    }

    private Map<String, String> ruleVersions(List<Rule> rules) {
        Map<String, String> versions = new java.util.LinkedHashMap<>();
        for (Rule rule : rules) {
            if (rule == null) continue;
            if (stateCompatibilityVersions.containsKey(rule.id())) {
                versions.put(rule.id(), stateCompatibilityVersions.get(rule.id()));
            } else if (rule instanceof StatefulRule stateful) {
                versions.put(rule.id(), stateful.stateVersion());
            } else {
                versions.put(rule.id(), "stateless-v1");
            }
        }
        return Map.copyOf(versions);
    }

    private List<DetectionResult.StateChange> stateChanges(Map<StatefulRule, byte[]> before,
                                                            List<Rule> rules) {
        if (before == null || before.isEmpty()) return List.of();
        List<DetectionResult.StateChange> changes = new ArrayList<>();
        for (Rule rule : rules) {
            if (!(rule instanceof StatefulRule stateful)) continue;
            byte[] original = before.get(stateful);
            byte[] current = stateful.snapshotState();
            changes.add(new DetectionResult.StateChange(rule.id(),
                    DetectionResult.digest(original), DetectionResult.digest(current),
                    !java.util.Arrays.equals(original, current)));
        }
        return List.copyOf(changes);
    }

    private void restoreMutableStates(Map<StatefulRule, byte[]> before) {
        before.forEach(StatefulRule::restoreState);
    }

    private Runnable commitGuard(WorkItem item) {
        return item.durableCommitGuard() == null ? durableCommitGuard : item.durableCommitGuard();
    }

    private void runCommitGuard(WorkItem item) {
        Runnable guard = commitGuard(item);
        if (guard != null) guard.run();
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
        return ingestAndAwait(event, onDurable, null);
    }

    /** Submit a durable event with an ownership guard specific to its state unit. */
    public CompletableFuture<Void> ingestAndAwait(SecurityEvent event, Runnable onDurable,
                                                  Runnable durableCommitGuard) {
        return ingestAndAwait(event, DetectionResult.InputPosition.unknown(), onDurable,
                durableCommitGuard);
    }

    /** Submit a durable event with its transport position attached to the result. */
    public CompletableFuture<Void> ingestAndAwait(SecurityEvent event,
                                                  DetectionResult.InputPosition inputPosition,
                                                  Runnable onDurable,
                                                  Runnable durableCommitGuard) {
        return submit(event, true, onDurable, durableCommitGuard, inputPosition).completion();
    }

    public Submission submit(SecurityEvent event, boolean durable) {
        return submit(event, durable, null);
    }

    public Submission submit(SecurityEvent event, boolean durable, Runnable onDurable) {
        return submit(event, durable, onDurable, null);
    }

    /** Submit with both a durable-position callback and a per-event fence. */
    public Submission submit(SecurityEvent event, boolean durable, Runnable onDurable,
                             Runnable durableCommitGuard) {
        return submit(event, durable, onDurable, durableCommitGuard,
                DetectionResult.InputPosition.unknown());
    }

    /** Submit with a transport position retained in the calculation result. */
    public Submission submit(SecurityEvent event, boolean durable, Runnable onDurable,
                             Runnable durableCommitGuard,
                             DetectionResult.InputPosition inputPosition) {
        CompletableFuture<Void> completion = new CompletableFuture<>();
        WorkItem item = new WorkItem(event, completion, durable, onDurable, durableCommitGuard,
                inputPosition == null ? DetectionResult.InputPosition.unknown() : inputPosition);
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
                    queue.put(new WorkItem(POISON_EVENT, null, false, null, null,
                            DetectionResult.InputPosition.unknown()));
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
            ruleCircuits.clear();
            old.forEach(Rule::close);
            log.info("Detection rules reloaded count={}", replacement.size());
        }
    }

    public List<Map<String, Object>> ruleStats() {
        return rulesRef.get().stream().map(rule -> {
            Map<String, Object> stats = new java.util.LinkedHashMap<>(rule.stats());
            RuleCircuitState circuit = ruleCircuits.get(rule.id());
            if (circuit != null) stats.putAll(circuit.stats());
            else {
                stats.put("ruleFailures", 0L);
                stats.put("ruleCircuit", "CLOSED");
            }
            return stats;
        }).toList();
    }

    private static final class RuleCircuitState {
        private long failures;
        private int consecutiveFailures;
        private long openUntilNanos;
        private String lastFailure;

        private synchronized void success() {
            if (openUntilNanos == 0L) consecutiveFailures = 0;
        }

        private synchronized boolean failure(Throwable failure, boolean permanent) {
            failures++;
            consecutiveFailures++;
            lastFailure = failure == null ? "unknown" : String.valueOf(failure.getMessage());
            if (permanent || consecutiveFailures >= RULE_FAILURE_THRESHOLD) {
                openUntilNanos = System.nanoTime() + RULE_FUSE_COOLDOWN_NANOS;
                return true;
            }
            return false;
        }

        private synchronized boolean open() {
            if (openUntilNanos == 0L) return false;
            if (System.nanoTime() < openUntilNanos) return true;
            openUntilNanos = 0L;
            consecutiveFailures = 0;
            return false;
        }

        private synchronized long failures() {
            return failures;
        }

        private synchronized Map<String, Object> stats() {
            boolean isOpen = open();
            Map<String, Object> out = new java.util.LinkedHashMap<>();
            out.put("ruleFailures", failures);
            out.put("ruleConsecutiveFailures", consecutiveFailures);
            out.put("ruleCircuit", isOpen ? "OPEN" : "CLOSED");
            if (lastFailure != null) out.put("ruleLastFailure", lastFailure);
            return out;
        }
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
                out.put(rule.id(), new RuleState(rule.id(), stateCompatibilityVersion(stateful),
                        stateful.snapshotState()));
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
                if (snapshot == null || !stateCompatibilityVersion(stateful).equals(snapshot.version())) continue;
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

    private String stateCompatibilityVersion(StatefulRule stateful) {
        return stateCompatibilityVersions.getOrDefault(stateful.id(), stateful.stateVersion());
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
