package com.socp.rule.engine;

import com.socp.rule.model.Alert;
import com.socp.rule.model.SecurityEvent;
import com.socp.rule.model.Severity;
import com.socp.rule.partition.DetectionRoutingKey;
import com.socp.rule.partition.DetectionDelivery;
import com.socp.rule.rules.Rule;
import com.socp.rule.state.StatefulRule;
import com.socp.rule.state.RuleStateMutation;
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
import java.util.concurrent.locks.ReentrantLock;

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
    /** Ordered marker that completes when every earlier accepted event has finished. */
    private static final SecurityEvent IDLE_BARRIER_EVENT = new SecurityEvent(
            Instant.EPOCH, "IDLE_BARRIER", "IDLE_BARRIER", "IDLE_BARRIER", Map.of(), Severity.INFO);

    private final AtomicReference<List<Rule>> rulesRef;
    private final List<AlertSink> sinks;
    private final Suppressor suppressor;
    private final RuleProcessingObserver observer;
    private final RuleExecutionScope executionScope;
    private final Runnable durableCommitGuard;
    private final Map<String, String> stateCompatibilityVersions;
    private static final int QUEUE_CAPACITY = 100_000;
    public static final int MAX_BATCH_EVENTS = 128;
    private final BlockingQueue<WorkItem> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    private final java.util.concurrent.Semaphore queuedEvents = new java.util.concurrent.Semaphore(QUEUE_CAPACITY);
    private final Map<String, RuleCircuitState> ruleCircuits = new java.util.concurrent.ConcurrentHashMap<>();
    private final ReentrantReadWriteLock lifecycle = new ReentrantReadWriteLock();
    private volatile boolean running = true;
    private Thread worker;

    private final AtomicLong eventCount = new AtomicLong();
    private final AtomicLong alertCount = new AtomicLong();
    private final AtomicLong dropCount = new AtomicLong();
    /**
     * Alerts this engine decided to suppress. The shared {@link Suppressor}
     * keeps a process-wide total, which cannot be attributed to a tenant or a
     * shard, so each engine counts its own decisions for statistics.
     */
    private final AtomicLong suppressedCount = new AtomicLong();
    private final AtomicLong routingMismatchWindows = new AtomicLong();
    /** Declared grouping dimension per rule; makes partition-locality observable. */
    private final Map<String, RoutingDimension> routingDimensions;
    private final Map<String, RoutingMismatchState> routingMismatches =
            new java.util.concurrent.ConcurrentHashMap<>();
    /** Serializes rule mutation, durable position callbacks, and snapshots. */
    // Durable evaluation performs JDBC I/O; a monitor would pin Java 21 carriers.
    private final ReentrantLock stateLock = new ReentrantLock();
    private static final int RULE_FAILURE_THRESHOLD = Math.max(1,
            Integer.getInteger("socp.rule.failure-threshold", 3));
    private static final long RULE_FUSE_COOLDOWN_NANOS = TimeUnit.SECONDS.toNanos(
            Math.max(1L, Long.getLong("socp.rule.fuse-cooldown-seconds", 60L)));

    /** Immediate queue admission plus an optional durable completion signal. */
    public record Submission(boolean accepted, CompletableFuture<Void> completion) {
    }

    /**
     * The event dimension a rule groups its state on, plus the cadence its
     * routing diagnostic may report at (normally one rule window).
     */
    public record RoutingDimension(String declaredField, long reportIntervalSeconds) {
    }

    public record BatchEvent(SecurityEvent event, DetectionResult.InputPosition position,
                             RuleExecutionScope scope) {
        public BatchEvent {
            java.util.Objects.requireNonNull(event, "event");
            java.util.Objects.requireNonNull(position, "position");
            java.util.Objects.requireNonNull(scope, "scope");
        }
    }

    private record WorkItem(SecurityEvent event, CompletableFuture<Void> completion,
                            boolean durable, Runnable onDurable, Runnable durableCommitGuard,
                            DetectionResult.InputPosition inputPosition, RuleExecutionScope scope,
                            List<BatchEvent> batch) {
        WorkItem(SecurityEvent event, CompletableFuture<Void> completion, boolean durable,
                 Runnable onDurable, Runnable guard, DetectionResult.InputPosition position,
                 RuleExecutionScope scope) {
            this(event, completion, durable, onDurable, guard, position, scope, List.of());
        }
        int weight() {
            return event == POISON_EVENT || event == IDLE_BARRIER_EVENT ? 0 : Math.max(1, batch.size());
        }
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
        this(rules, sinks, suppressor, observer, executionScope, durableCommitGuard,
                stateCompatibilityVersions, Map.of());
    }

    /**
     * Create an engine that also knows each rule's declared grouping dimension.
     * The engine compares it with the dimension the event actually routes on so
     * a partial-state rule is observable instead of only documented.
     */
    public RuleEngine(List<Rule> rules, List<AlertSink> sinks, Suppressor suppressor,
                      RuleProcessingObserver observer, RuleExecutionScope executionScope,
                      Runnable durableCommitGuard, Map<String, String> stateCompatibilityVersions,
                      Map<String, RoutingDimension> routingDimensions) {
        this.rulesRef = new AtomicReference<>(List.copyOf(rules));
        this.sinks = List.copyOf(sinks);
        this.suppressor = suppressor;
        this.observer = observer == null ? RuleProcessingObserver.NOOP : observer;
        this.executionScope = executionScope == null ? RuleExecutionScope.NOOP : executionScope;
        this.durableCommitGuard = durableCommitGuard;
        this.stateCompatibilityVersions = stateCompatibilityVersions == null
                ? Map.of() : Map.copyOf(stateCompatibilityVersions);
        this.routingDimensions = routingDimensions == null ? Map.of() : Map.copyOf(routingDimensions);
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
        stateLock.lock();
        try {
            for (SecurityEvent event : history) {
                if (DetectionDelivery.isRouted(event)
                        && DetectionDelivery.kind(event) != DetectionDelivery.Kind.STATEFUL) {
                    continue;
                }
                List<Rule> selected = rulesFor(event);
                for (Rule rule : selected) rule.accept(event);
                for (Rule rule : selected) rule.drain();
            }
        } finally {
            stateLock.unlock();
        }
        log.info("Detection rule state restored events={}", history.size());
    }

    private void loop() {
        while (true) {
            try {
                WorkItem item = queue.take();
                queuedEvents.release(item.weight());
                if (item.event() == POISON_EVENT) break;
                if (item.event() == IDLE_BARRIER_EVENT) {
                    if (item.completion() != null) item.completion().complete(null);
                    continue;
                }
                try {
                    if (item.batch().isEmpty()) process(item);
                    else processBatch(item);
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
        stateLock.lock();
        try {
            SecurityEvent event = item.event();
            List<Rule> selected = rulesFor(event);
            try (StateMutations mutations = beginMutations(item.durable() ? selected : List.of())) {
                Map<StatefulRule, RuleStateMutation> before = mutations.values();
                try (RuleExecutionScope.Scope ignored = item.scope().open(event)) {
                    processInScope(item, event, before, selected, null);
                }
                // Position bookkeeping is deliberately inside the same
                // critical section as state mutation. A checkpoint can
                // therefore never capture rule bytes ahead of its watermark.
                if (item.onDurable() != null) item.onDurable().run();
                mutations.commit();
            } catch (RuntimeException | Error failure) {
                // Candidate alerts are not covered by the serialized state
                // snapshot. Without this drain, an alert a rule emitted before
                // it failed would be delivered against the next event.
                discardPendingAlerts();
                throw failure;
            }
        } finally {
            stateLock.unlock();
        }
    }

    public boolean supportsAtomicBatch() {
        return sinks.size() == 1 && sinks.getFirst() instanceof BatchEventAlertSink sink
                && sink.supportsAtomicBatch();
    }

    /** The engine owns all state until the atomic result transaction is acknowledged. */
    private void processBatch(WorkItem batchItem) {
        stateLock.lock();
        Map<StatefulRule, byte[]> before = new java.util.LinkedHashMap<>();
        boolean committed = false;
        try {
            if (!supportsAtomicBatch()) throw new IllegalStateException("atomic batch sink unavailable");
            // One complete recovery boundary per bounded batch, never one per event.
            // Per-event entity undo still produces the original audit envelope.
            for (Rule rule : rulesRef.get()) {
                if (rule instanceof StatefulRule stateful) before.put(stateful, stateful.snapshotState());
            }
            List<DetectionResult> results = new ArrayList<>(batchItem.batch().size());
            for (BatchEvent input : batchItem.batch()) {
                WorkItem item = new WorkItem(input.event(), null, true, null,
                        batchItem.durableCommitGuard(), input.position(), input.scope());
                List<Rule> selected = rulesFor(input.event());
                try (StateMutations mutations = beginMutations(selected);
                     RuleExecutionScope.Scope ignored = input.scope().open(input.event())) {
                    processInScope(item, input.event(), mutations.values(), selected, results);
                    mutations.commit();
                }
            }
            // Evaluate first, then reserve only the suppression keys this batch actually touches.
            // Taking their stripes in one fixed order avoids cross-engine deadlocks without
            // holding process-wide locks through snapshots or unrelated database transactions.
            List<Alert> candidates = results.stream().flatMap(result -> result.candidates().stream()).toList();
            try (Suppressor.Transaction suppression = suppressor == null
                    ? null : suppressor.beginTransaction(candidates)) {
                for (int index = 0; index < results.size(); index++) {
                    DetectionResult evaluated = results.get(index);
                    try (RuleExecutionScope.Scope ignored = batchItem.batch().get(index).scope().open(evaluated.event());
                         Suppressor.Batch reservation = suppressor == null ? null : suppressor.begin(evaluated.candidates())) {
                        List<Alert> emitted = reservation == null ? evaluated.candidates() : reservation.alerts();
                        suppressedCount.addAndGet(Math.max(0, evaluated.candidates().size() - emitted.size()));
                        notifyEvaluationCompleted(evaluated.event(), emitted.size());
                        results.set(index, new DetectionResult(evaluated.event(), evaluated.inputPosition(),
                                evaluated.ruleVersions(), evaluated.stateChanges(), evaluated.candidates(), emitted,
                                reservation == null ? DetectionResult.SuppressionDecision.none(evaluated.candidates(), emitted)
                                        : DetectionResult.SuppressionDecision.window(evaluated.candidates(), emitted),
                                evaluated.idempotencyKey()));
                        if (reservation != null) reservation.commit();
                    }
                }
                try (RuleExecutionScope.Scope ignored = batchItem.scope().open(batchItem.event())) {
                    ((BatchEventAlertSink) sinks.getFirst()).publishBatch(List.copyOf(results),
                            batchItem.batch().stream().map(BatchEvent::scope).toList(), commitGuard(batchItem));
                }
                committed = true;
                if (suppression != null) suppression.commit();
                for (DetectionResult result : results) {
                    alertCount.addAndGet(result.alerts().size());
                    notifyDurableSinksCompleted(result.event(), result.alerts().size());
                }
                // Every position is installed together, before any checkpoint can see the batch state.
                if (batchItem.onDurable() != null) batchItem.onDurable().run();
            }
        } catch (RuntimeException | Error failure) {
            if (!committed) {
                before.forEach((rule, bytes) -> {
                    try { rule.restoreState(bytes); }
                    catch (RuntimeException rollback) { failure.addSuppressed(rollback); }
                });
            }
            discardPendingAlerts();
            throw failure;
        } finally {
            stateLock.unlock();
        }
    }

    /** Discard every candidate alert accumulated since the last drain. */
    private void discardPendingAlerts() {
        for (Rule rule : rulesRef.get()) {
            try {
                rule.drain();
            } catch (RuntimeException drainFailure) {
                log.warn("Unable to discard candidate alerts ruleId={}: {}",
                        rule.id(), drainFailure.getMessage());
            }
        }
    }

    private void processInScope(WorkItem item, SecurityEvent event,
                                Map<StatefulRule, RuleStateMutation> before,
                                List<Rule> rules, List<DetectionResult> buffered) {
        eventCount.incrementAndGet();
        if (!DetectionDelivery.isRouted(event)) observeRoutingDimensions(event);
        for (Rule rule : rules) {
            if (ruleCircuitOpen(rule)) {
                log.warn("Skipping isolated detection rule ruleId={} eventId={}", rule.id(), event.id());
                continue;
            }
            acceptRule(rule, event, before);
        }

        List<Alert> candidates = new ArrayList<>();
        for (Rule rule : rules) candidates.addAll(rule.drain());
        if (buffered != null) {
            // Capture each event's audit diff before the next event mutates rule state.
            // Suppression is resolved once all candidate keys are known to the batch.
            buffered.add(new DetectionResult(event, item.inputPosition(), ruleVersions(rules),
                    stateChanges(before, rules), candidates, candidates,
                    DetectionResult.SuppressionDecision.none(candidates, candidates),
                    DetectionDelivery.isRouted(event) ? DetectionDelivery.deliveryId(event) : event.scopedId()));
            return;
        }
        try (Suppressor.Batch batch = suppressor == null ? null : suppressor.begin(candidates)) {
            List<Alert> emitted = batch == null ? List.copyOf(candidates) : batch.alerts();
            suppressedCount.addAndGet(Math.max(0, candidates.size() - emitted.size()));
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
                    DetectionDelivery.isRouted(event)
                            ? DetectionDelivery.deliveryId(event) : event.scopedId());
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

    /** Isolate permanent rule errors while retaining whole-event rollback on sink failure. */
    private void acceptRule(Rule rule, SecurityEvent event,
                            Map<StatefulRule, RuleStateMutation> durableBefore) {
        RuleStateMutation mutation = rule instanceof StatefulRule stateful
                ? durableBefore.get(stateful) : null;
        boolean owned = mutation == null && rule instanceof StatefulRule;
        if (owned) mutation = ((StatefulRule) rule).beginMutation();
        try {
            boolean instrumented = observer != RuleProcessingObserver.NOOP;
            long started = instrumented ? System.nanoTime() : 0L;
            rule.accept(event);
            if (instrumented) {
                notifyRuleEvaluated(rule.id(), System.nanoTime() - started);
            }
            ruleCircuits.computeIfAbsent(rule.id(), ignored -> new RuleCircuitState()).success();
            if (owned) mutation.commit();
        } catch (RuntimeException failure) {
            if (mutation != null) {
                try {
                    mutation.rollback();
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
        } finally {
            if (owned) mutation.close();
        }
    }

    /**
     * Report a rule whose declared grouping dimension is not the dimension this
     * event routes on, so the state it accumulates is only a fragment of the
     * entity's history. The check costs one routing resolution per event and is
     * reported at most once per rule per rule window, which keeps it usable on
     * the event path without bounding alert volume.
     */
    private void observeRoutingDimensions(SecurityEvent event) {
        if (routingDimensions.isEmpty()) return;
        String eventField = null;
        for (Map.Entry<String, RoutingDimension> declared : routingDimensions.entrySet()) {
            RoutingDimension dimension = declared.getValue();
            if (dimension == null || dimension.declaredField() == null
                    || dimension.declaredField().isBlank()) continue;
            if (DetectionRoutingKey.isPartitionLocal(event, dimension.declaredField())) continue;
            if (eventField == null) {
                eventField = DetectionRoutingKey.field(event.source(), event.host(), event.fields());
            }
            RoutingMismatchState state = routingMismatches.computeIfAbsent(
                    declared.getKey(), ignored -> new RoutingMismatchState());
            if (!state.windowElapsed(dimension.reportIntervalSeconds())) continue;
            routingMismatchWindows.incrementAndGet();
            log.warn("Detection rule state is not partition-local ruleId={} declaredGrouping={} "
                            + "eventRoutingField={} eventId={}",
                    declared.getKey(), dimension.declaredField(), eventField, event.id());
            notifyRoutingMismatch(event, declared.getKey(), dimension.declaredField(), eventField);
        }
    }

    private void notifyRoutingMismatch(SecurityEvent event, String ruleId,
                                       String declaredField, String eventField) {
        try {
            observer.routingMismatched(event, ruleId, declaredField, eventField);
        } catch (RuntimeException metricsFailure) {
            log.debug("Rule processing observer failed at routing diagnostic: {}",
                    metricsFailure.getMessage());
        }
    }

    private static final class RoutingMismatchState {
        private long lastReportNanos;
        private long reports;

        /** True once per reporting interval; a window without this event stays quiet. */
        private synchronized boolean windowElapsed(long reportIntervalSeconds) {
            long now = System.nanoTime();
            long interval = TimeUnit.SECONDS.toNanos(Math.max(1L, reportIntervalSeconds));
            if (lastReportNanos != 0L && now - lastReportNanos < interval) return false;
            lastReportNanos = now;
            reports++;
            return true;
        }

        private synchronized long reports() {
            return reports;
        }
    }

    private boolean ruleCircuitOpen(Rule rule) {
        RuleCircuitState circuit = ruleCircuits.get(rule.id());
        return circuit != null && circuit.open();
    }

    private StateMutations beginMutations(List<Rule> rules) {
        StateMutations mutations = new StateMutations(new java.util.LinkedHashMap<>());
        try {
            for (Rule rule : rules) {
                if (rule instanceof StatefulRule stateful) {
                    mutations.values().put(stateful, stateful.beginMutation());
                }
            }
            return mutations;
        } catch (RuntimeException | Error failure) {
            try { mutations.close(); } catch (RuntimeException rollback) { failure.addSuppressed(rollback); }
            throw failure;
        }
    }

    private record StateMutations(Map<StatefulRule, RuleStateMutation> values) implements AutoCloseable {
        void commit() { values.values().forEach(RuleStateMutation::commit); }

        @Override
        public void close() {
            RuntimeException failure = null;
            for (RuleStateMutation mutation : values.values()) {
                try { mutation.close(); }
                catch (RuntimeException rollback) {
                    if (failure == null) failure = rollback;
                    else failure.addSuppressed(rollback);
                }
            }
            if (failure != null) throw failure;
        }
    }

    /**
     * Routed deliveries deliberately split execution classes. Stateless rules
     * execute only on the singleton STATELESS copy; a STATEFUL copy executes
     * only rules that own its grouping dimension. Legacy/direct events retain
     * the original all-rules behavior for migration and focused tests.
     */
    private List<Rule> rulesFor(SecurityEvent event) {
        List<Rule> rules = rulesRef.get();
        if (!DetectionDelivery.isRouted(event)) return rules;
        DetectionDelivery.Kind kind = DetectionDelivery.kind(event);
        if (kind == DetectionDelivery.Kind.STATELESS) {
            return rules.stream().filter(rule -> !(rule instanceof StatefulRule)).toList();
        }
        if (kind != DetectionDelivery.Kind.STATEFUL) return List.of();
        String dimension = DetectionDelivery.dimension(event);
        if (dimension == null || dimension.isBlank()) return List.of();
        return rules.stream()
                .filter(StatefulRule.class::isInstance)
                .filter(rule -> {
                    RoutingDimension declared = routingDimensions.get(rule.id());
                    return declared != null && dimension.equals(declared.declaredField());
                })
                .toList();
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

    private List<DetectionResult.StateChange> stateChanges(Map<StatefulRule, RuleStateMutation> before,
                                                          List<Rule> rules) {
        if (before.isEmpty()) return List.of();
        List<DetectionResult.StateChange> changes = new ArrayList<>();
        for (Rule rule : rules) {
            if (rule instanceof StatefulRule stateful) {
                changes.add(before.get(stateful).change(rule.id()));
            }
        }
        return List.copyOf(changes);
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

    private void notifyRuleEvaluated(String ruleId, long nanos) {
        try {
            observer.ruleEvaluated(ruleId, nanos);
        } catch (RuntimeException metricsFailure) {
            log.debug("Rule processing observer failed at rule boundary ruleId={}: {}",
                    ruleId, metricsFailure.getMessage());
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
                inputPosition == null ? DetectionResult.InputPosition.unknown() : inputPosition,
                executionScope.capture());
        return enqueueItem(item);
    }

    public CompletableFuture<Void> ingestBatchAndAwait(List<BatchEvent> events, Runnable onDurable,
                                                       Runnable ownershipGuard) {
        if (events == null || events.isEmpty() || events.size() > MAX_BATCH_EVENTS) {
            throw new IllegalArgumentException("batch must contain 1.." + MAX_BATCH_EVENTS + " events");
        }
        if (!supportsAtomicBatch()) throw new IllegalStateException("atomic batch sink unavailable");
        List<BatchEvent> batch = List.copyOf(events);
        BatchEvent first = batch.getFirst();
        var ids = new java.util.HashSet<String>();
        for (BatchEvent input : batch) {
            if (!first.event().requireTenantId().equals(input.event().requireTenantId())
                    || !ids.add(DetectionDelivery.deliveryId(input.event()))) {
                throw new IllegalArgumentException("batch requires one tenant and unique delivery identities");
            }
        }
        CompletableFuture<Void> completion = new CompletableFuture<>();
        return enqueueItem(new WorkItem(first.event(), completion, true, onDurable, ownershipGuard,
                first.position(), first.scope(), batch)).completion();
    }

    private Submission enqueueItem(WorkItem item) {
        CompletableFuture<Void> completion = item.completion();
        boolean reserved = false;
        lifecycle.readLock().lock();
        try {
            if (!running) {
                completion.completeExceptionally(new IllegalStateException("detection engine is closed"));
                return new Submission(false, completion);
            }
            reserved = queuedEvents.tryAcquire(item.weight());
            if (!reserved) {
                try { reserved = queuedEvents.tryAcquire(item.weight(), 50, TimeUnit.MILLISECONDS); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            }
            if (!reserved) {
                dropCount.addAndGet(item.weight());
                completion.completeExceptionally(new IllegalStateException("detection queue full"));
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
        if (reserved) queuedEvents.release(item.weight());
        dropCount.addAndGet(item.weight());
        completion.completeExceptionally(new IllegalStateException("detection queue full"));
        return new Submission(false, completion);
    }

    public double queueLoad() {
        return (double) (QUEUE_CAPACITY - queuedEvents.availablePermits()) / QUEUE_CAPACITY;
    }

    /**
     * Wait until every event accepted before this call has finished, leaving the
     * engine open to new work. A hot reload drains the live engine with this
     * before it reads the durable journal, so the replacement cannot miss a
     * completion that was still in flight - and unlike {@link #close()} the
     * engine survives a failed rebuild instead of taking the tenant down.
     *
     * @return true when the engine reached an idle point within the budget
     */
    public boolean awaitIdle(long timeoutMs) {
        if (!running) return true;
        long budget = Math.max(1L, timeoutMs);
        CompletableFuture<Void> barrier = new CompletableFuture<>();
        lifecycle.readLock().lock();
        try {
            if (!running) return true;
            WorkItem item = new WorkItem(IDLE_BARRIER_EVENT, barrier, false, null, null,
                    DetectionResult.InputPosition.unknown(), RuleExecutionScope.NOOP);
            if (!queue.offer(item, budget, TimeUnit.MILLISECONDS)) {
                log.warn("Detection engine is saturated; unable to schedule an idle barrier");
                return false;
            }
        } catch (InterruptedException interruption) {
            Thread.currentThread().interrupt();
            return false;
        } finally {
            lifecycle.readLock().unlock();
        }
        try {
            barrier.get(budget, TimeUnit.MILLISECONDS);
            return true;
        } catch (Exception incomplete) {
            log.warn("Detection engine did not drain within {} ms: {}", budget, incomplete.getMessage());
            return false;
        }
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
                            DetectionResult.InputPosition.unknown(), RuleExecutionScope.NOOP));
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
        stateLock.lock();
        try {
            List<Rule> old = rulesRef.getAndSet(replacement);
            ruleCircuits.clear();
            routingMismatches.clear();
            old.forEach(Rule::close);
            log.info("Detection rules reloaded count={}", replacement.size());
        } finally {
            stateLock.unlock();
        }
    }

    public List<Map<String, Object>> ruleStats() {
        // stats() may expire idle keys; it must not race an event's undo boundary.
        stateLock.lock();
        try {
            return rulesRef.get().stream().map(rule -> {
                Map<String, Object> stats = new java.util.LinkedHashMap<>(rule.stats());
                RuleCircuitState circuit = ruleCircuits.get(rule.id());
                if (circuit != null) stats.putAll(circuit.stats());
                else {
                    stats.put("ruleFailures", 0L);
                    stats.put("ruleCircuit", "CLOSED");
                }
                RoutingDimension dimension = routingDimensions.get(rule.id());
                if (dimension != null) {
                    stats.put("routingField", dimension.declaredField());
                    RoutingMismatchState mismatch = routingMismatches.get(rule.id());
                    stats.put("routingMismatchWindows", mismatch == null ? 0L : mismatch.reports());
                }
                return stats;
            }).toList();
        } finally {
            stateLock.unlock();
        }
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
            lastFailure = failure == null ? "unknown" : String.valueOf(failure.getMessage());
            // Dependency outages (including corrupt external state) must never
            // be acknowledged as successful evaluations after enough retries.
            if (RuleDependencyException.causedBy(failure)) return false;
            consecutiveFailures++;
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
        stateLock.lock();
        try {
            Map<String, RuleState> out = new java.util.LinkedHashMap<>();
            for (Rule rule : rulesRef.get()) {
                if (!(rule instanceof StatefulRule stateful)) continue;
                out.put(rule.id(), new RuleState(rule.id(), stateCompatibilityVersion(stateful),
                        stateful.snapshotState()));
            }
            return capture.apply(Map.copyOf(out));
        } finally {
            stateLock.unlock();
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
        stateLock.lock();
        try {
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
        } finally {
            stateLock.unlock();
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

    /**
     * Alerts suppressed by this engine's own decisions. Safe to sum across the
     * engines of a tenant because it is instance state, unlike the process-wide
     * total kept inside the shared {@link Suppressor}.
     */
    public long suppressedCount() {
        return suppressedCount.get();
    }

    /** Routing diagnostics this engine reported; at most one per rule window. */
    public long routingMismatchWindows() {
        return routingMismatchWindows.get();
    }
}
