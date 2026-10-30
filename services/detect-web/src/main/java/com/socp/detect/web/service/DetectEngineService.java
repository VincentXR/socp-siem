package com.socp.detect.web.service;

import com.socp.detect.web.engine.RecentAlertSink;
import com.socp.detect.web.engine.TenantAdmission;
import com.socp.detect.web.persistence.store.DetectionStateStore;
import com.socp.detect.web.persistence.store.DetectionEventClaim;
import com.socp.detect.web.persistence.store.DetectionStateOwnership;
import com.socp.detect.web.persistence.store.InMemoryDetectionStateStore;
import com.socp.detect.web.persistence.store.RuleSpecStore;
import com.socp.rule.engine.RuleEngine;
import com.socp.rule.engine.RuleProcessingObserver;
import com.socp.rule.model.Alert;
import com.socp.rule.model.SecurityEvent;
import com.socp.rule.state.DetectionStateSnapshotStore;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Detection runtime admission and durable ingestion. Rule catalogue transactions
 * are owned by {@link DetectionRuleService}; engine replacement and durable
 * recovery are coordinated by their owning lifecycle and state components.
 */
@Service
public class DetectEngineService {
    /** Recovery is fail-closed: detection accepts work only after its state is ready. */
    public enum RecoveryStatus {
        READY,
        RECOVERING,
        DEGRADED
    }

    /**
     * Typed runtime gate used by the Kafka boundary. Role mismatch and state
     * recovery are worker conditions, never poison-record evidence.
     */
    public static final class RuntimeUnavailableException extends IllegalStateException {
        public RuntimeUnavailableException(String message) {
            super(message);
        }
    }

    private final RuleSpecStore store;
    private final RecentAlertSink sink;
    private final DetectionStateStore stateStore;
    private final DetectionEngineLifecycle lifecycle;
    private final DetectionStateRecovery state;
    private final TenantAdmission tenantAdmission = new TenantAdmission();

    @Value("${socp.detect.tenant.max-events-per-second:0}")
    private long tenantMaxEventsPerSecond = 0L;

    @Value("${socp.detect.tenant.rate-burst:100}")
    private long tenantRateBurst = 100L;

    @Value("${socp.detect.tenant.max-pending-bytes:67108864}")
    private long tenantMaxPendingBytes = 64L * 1024 * 1024;

    @Value("${socp.detect.tenant.max-active-entities:100000}")
    private int tenantMaxActiveEntities = 100_000;

    @Value("${socp.detect.tenant.entity-idle-ttl-ms:1800000}")
    private long tenantEntityIdleTtlMs = 30 * 60 * 1000L;

    /**
     * Operator label for this worker. Recovery and isolation counters are engine
     * state inside one process, so a stats reader must know which replica it is
     * looking at.
     */
    @Value("${socp.detect.instance-id:unknown}")
    private String instanceId = "unknown";

    @Autowired
    public DetectEngineService(RuleSpecStore store, RecentAlertSink sink,
                               DetectionStateStore stateStore, DetectionEngineLifecycle lifecycle,
                               DetectionStateRecovery state) {
        this.store = store;
        this.sink = sink;
        this.stateStore = stateStore;
        this.lifecycle = lifecycle;
        this.state = state;
    }

    /** Standalone runtime for tests and in-process callers without a Spring container. */
    DetectEngineService(RuleSpecStore store, RecentAlertSink sink, DetectionStateStore stateStore,
                        RuleProcessingObserver observer, DetectionStateSnapshotStore snapshots,
                        DetectionStateOwnership ownership) {
        this.store = store;
        this.sink = sink;
        this.stateStore = stateStore;
        this.state = new DetectionStateRecovery(stateStore, snapshots, ownership);
        this.lifecycle = new DetectionEngineLifecycle(store, sink, observer, state);
    }

    DetectEngineService(RuleSpecStore store, RecentAlertSink sink, DetectionStateStore stateStore,
                        RuleProcessingObserver observer, DetectionStateSnapshotStore snapshots) {
        this(store, sink, stateStore, observer, snapshots, DetectionStateOwnership.noop());
    }

    DetectEngineService(RuleSpecStore store, RecentAlertSink sink, DetectionStateStore stateStore) {
        this(store, sink, stateStore, RuleProcessingObserver.NOOP, null);
    }

    DetectEngineService(RuleSpecStore store, RecentAlertSink sink) {
        this(store, sink, new InMemoryDetectionStateStore());
    }

    @PostConstruct
    public void start() { lifecycle.start(); }

    @PreDestroy
    public void stop() {
        lifecycle.stop();
        tenantAdmission.clear();
    }

    public RecoveryStatus recoveryStatus() { return lifecycle.recoveryStatus(); }

    public boolean isReady() { return lifecycle.isReady(); }

    public String recoveryFailure() { return lifecycle.recoveryFailure(); }

    public void reload() { lifecycle.reload(); }

    public void releaseForPartitions(Set<Integer> partitions) { lifecycle.releaseForPartitions(partitions); }

    public void restoreForPartitions(Set<Integer> partitions) { lifecycle.restoreForPartitions(partitions); }

    public void rebuildForPartitions(Set<Integer> partitions) { lifecycle.rebuildForPartitions(partitions); }

    public void restoreAll() { lifecycle.restoreAll(); }

    public Set<Integer> assignedPartitions() { return state.assignedPartitions(); }

    /** Valid only after the ingestion future succeeds, including its ownership callback. */
    public boolean completesJournal(DetectionStateStore expectedStore) {
        return expectedStore == stateStore && sink.completesJournal(expectedStore);
    }

    /** Journal finalization must use the same ownership fence as durable rule effects. */
    public void assertCurrentOwner(SecurityEvent event, Integer partition) {
        lifecycle.readLock().lock();
        try {
            state.assertCurrentOwner(event, partition);
        } finally {
            lifecycle.readLock().unlock();
        }
    }

    /** 事件摄取：队列满回 false（接入端据此回 503 + Retry-After） */
    public boolean ingest(SecurityEvent ev) {
        lifecycle.readLock().lock();
        try {
            int shard = state.shardFor(ev);
            if (!lifecycle.workerRole() || !lifecycle.readyFor(ev.tenantId(), shard)) return false;
            DetectionEventClaim claim = stateStore.claim(ev);
            if (claim == DetectionEventClaim.COMPLETED || claim == DetectionEventClaim.DEAD_LETTERED) {
                return true;
            }
            TenantAdmission.Decision admission = admit(ev);
            if (!admission.admitted()) {
                if (claim == DetectionEventClaim.NEW) stateStore.remove(ev);
                return false;
            }
            TenantAdmission.Permit permit = admission.permit();
            RuleEngine.Submission submission;
            try {
                RuleEngine target = lifecycle.engineFor(ev.tenantId(), shard);
                // HTTP ingestion has no Kafka position to acknowledge. The
                // completion handler below owns its checkpoint cadence; adding
                // a worker callback here would count every event twice.
                submission = target.submit(ev, true);
            } catch (RuntimeException recoveryFailure) {
                tenantAdmission.rollback(permit);
                if (claim == DetectionEventClaim.NEW) stateStore.remove(ev);
                return false;
            }
            if (!submission.accepted()) {
                tenantAdmission.rollback(permit);
                if (claim == DetectionEventClaim.NEW) stateStore.remove(ev);
                return false;
            }
            submission.completion().whenComplete((ignored, failure) -> {
                tenantAdmission.release(permit);
                if (failure == null) {
                    if (!completesJournal(stateStore)) stateStore.markCompleted(ev);
                    snapshotAfterDurable(ev, null, -1L);
                }
            });
            return true;
        } finally {
            lifecycle.readLock().unlock();
        }
    }

    /**
     * Kafka path after the consumer has atomically claimed the event id in the
     * same durable state store. Keeping this separate prevents a second claim
     * while preserving the retry/delete behavior when the bounded queue is full.
     */
    public boolean ingestFromKafka(SecurityEvent ev) {
        return enqueue(ev);
    }

    /** Completion is signalled only after EventAlertSink durable effects return. */
    public CompletableFuture<Void> ingestFromKafkaAndAwait(SecurityEvent ev) {
        return ingestFromKafkaAndAwait(ev, null, null);
    }

    /** Kafka ingestion with a position callback ordered before completion. */
    public CompletableFuture<Void> ingestFromKafkaAndAwait(SecurityEvent ev,
                                                           Integer partition, Long offset) {
        return ingestFromKafkaAndAwait(ev, state.inputTopic(), partition, offset);
    }

    /** Internal Kafka admission identity; all members must target one pinned worker. */
    public record BatchKey(String tenant, int shard) { }

    public record KafkaInput(SecurityEvent event, String topic, Integer partition, Long offset,
                             org.apache.kafka.common.header.Headers headers) {
        public KafkaInput(SecurityEvent event, String topic, Integer partition, Long offset) {
            this(event, topic, partition, offset, null);
        }
    }

    public BatchKey batchKey(SecurityEvent event) {
        return new BatchKey(event.requireTenantId(), state.shardFor(event));
    }

    /**
     * Submit a bounded, ordered partition group while pinning one engine generation.
     * A failed member cancels uncommitted successors; callers must settle the whole
     * group before retrying its failed suffix from fresh journal receipts.
     */
    public java.util.List<CompletableFuture<Void>> ingestBatchFromKafkaAndAwait(java.util.List<KafkaInput> inputs) {
        return ingestBatchFromKafkaAndAwait(inputs, () -> true);
    }

    /** Revalidate the caller's exact assignment after waiting for engine replacement. */
    public java.util.List<CompletableFuture<Void>> ingestBatchFromKafkaAndAwait(
            java.util.List<KafkaInput> inputs, java.util.function.BooleanSupplier assignmentActive) {
        java.util.Objects.requireNonNull(assignmentActive, "assignmentActive");
        if (inputs == null || inputs.isEmpty() || inputs.size() > 64) {
            throw new IllegalArgumentException("Kafka admission batch must contain 1..64 events");
        }
        KafkaInput first = inputs.getFirst();
        BatchKey key = batchKey(first.event());
        java.util.Set<String> deliveries = new java.util.HashSet<>();
        long previousOffset = -1L;
        for (KafkaInput input : inputs) {
            if (input.partition() == null || input.partition() < 0 || input.offset() == null || input.offset() < 0
                    || input.topic() == null || input.topic().isBlank()
                    || !java.util.Objects.equals(first.topic(), input.topic())
                    || !java.util.Objects.equals(first.partition(), input.partition())
                    || !key.equals(batchKey(input.event())) || input.offset() <= previousOffset
                    || !deliveries.add(com.socp.rule.partition.DetectionDelivery.deliveryId(input.event()))) {
                throw new IllegalArgumentException("Kafka batch requires one tenant, shard, partition and ordered distinct deliveries");
            }
            previousOffset = input.offset();
        }
        RuleEngine.OrderedBatch ordered = new RuleEngine.OrderedBatch();
        java.util.List<CompletableFuture<Void>> completions = new java.util.ArrayList<>(inputs.size());
        lifecycle.readLock().lock();
        try (var tenantScope = com.socp.platform.tenant.context.TenantContext.open(key.tenant())) {
            for (KafkaInput input : inputs) {
                CompletableFuture<Void> completion;
                try {
                    if (ordered.failure() == null && (Thread.currentThread().isInterrupted()
                            || !assignmentActive.getAsBoolean())) {
                        ordered.fail(new RuntimeUnavailableException("Kafka admission assignment is no longer active"));
                    }
                    completion = ordered.failure() == null
                            ? com.socp.platform.client.kafka.KafkaTrace.callConsumed("detect " + input.topic() + " receive",
                                    input.headers(), () -> ingestFromKafkaAndAwait(input.event(), input.topic(),
                                            input.partition(), input.offset(), ordered))
                            : CompletableFuture.failedFuture(ordered.failure());
                } catch (RuntimeException admissionFailure) {
                    ordered.fail(admissionFailure);
                    completion = CompletableFuture.failedFuture(ordered.failure());
                }
                completion.whenComplete((ignored, failure) -> {
                    if (failure != null) ordered.fail(failure);
                });
                completions.add(completion);
            }
            return java.util.List.copyOf(completions);
        } finally {
            lifecycle.readLock().unlock();
        }
    }

    /** Kafka ingestion carrying the source topic into the durable result envelope. */
    public CompletableFuture<Void> ingestFromKafkaAndAwait(SecurityEvent ev, String topic,
                                                           Integer partition, Long offset) {
        return ingestFromKafkaAndAwait(ev, topic, partition, offset, null);
    }

    private CompletableFuture<Void> ingestFromKafkaAndAwait(SecurityEvent ev, String topic,
                                                           Integer partition, Long offset,
                                                           RuleEngine.OrderedBatch ordered) {
        lifecycle.readLock().lock();
        try {
            if (Thread.currentThread().isInterrupted()) {
                return CompletableFuture.failedFuture(new RuntimeUnavailableException("Kafka admission interrupted"));
            }
            if (!lifecycle.workerRole()) {
                return CompletableFuture.failedFuture(
                        new RuntimeUnavailableException("detection runtime role is " + lifecycle.runtimeRole()));
            }
            int shard = state.shardFor(ev);
            if (!lifecycle.readyFor(ev.requireTenantId(), shard)) {
                return CompletableFuture.failedFuture(new RuntimeUnavailableException(
                        "detection state recovery is "
                                + lifecycle.recoveryStatusFor(ev.requireTenantId(), shard).name()));
            }
            TenantAdmission.Decision admission = admit(ev);
            if (!admission.admitted()) {
                return CompletableFuture.failedFuture(new TenantAdmission.RejectedException(
                        ev.requireTenantId(), admission.reason()));
            }
            TenantAdmission.Permit permit = admission.permit();
            try {
                RuleEngine target = lifecycle.engineFor(ev.tenantId(), shard);
                int statePartition = partition == null ? -1 : partition;
                Runnable ownershipGuard = state.durableGuardFor(statePartition, shard);
                boolean atomicallyFenced = sink.completesJournal(stateStore);
                Runnable durablePosition = () -> {
                    // The primary transaction already held the owner row through commit.
                    // Checkpoints acquire their own fence; this callback records memory only.
                    if (!atomicallyFenced) ownershipGuard.run();
                    state.recordDurablePosition(ev, partition, offset);
                };
                com.socp.rule.engine.DetectionResult.InputPosition inputPosition =
                        partition == null || offset == null
                                ? com.socp.rule.engine.DetectionResult.InputPosition.unknown()
                                : new com.socp.rule.engine.DetectionResult.InputPosition(
                                topic, partition, offset);
                CompletableFuture<Void> durable = target.ingestAndAwait(
                        ev, inputPosition, durablePosition, ownershipGuard, ordered);
                // Keep the snapshot tied to the engine instance that processed
                // the event. A concurrent hot reload can replace the map entry
                // before the caller observes completion; looking the engine up
                // again there could checkpoint the fresh, empty replacement.
                return durable.thenRun(() -> state.snapshotAfterDurable(target, ev, partition, offset))
                        .whenComplete((ignored, failure) -> tenantAdmission.release(permit));
            } catch (RuntimeException recoveryFailure) {
                tenantAdmission.rollback(permit);
                return CompletableFuture.failedFuture(recoveryFailure);
            }
        } finally {
            lifecycle.readLock().unlock();
        }
    }

    /** Persist a versioned rule-state checkpoint after durable sink completion. */
    public void snapshotAfterDurable(SecurityEvent event, Integer partition, Long offset) {
        if (!state.snapshotsEnabled() || event == null) return;
        state.snapshotAfterDurable(lifecycle.engineFor(event.tenantId(), state.shardFor(event)), event, partition, offset);
    }

    private boolean enqueue(SecurityEvent ev) {
        lifecycle.readLock().lock();
        try {
            int shard = state.shardFor(ev);
            if (!lifecycle.workerRole() || !lifecycle.readyFor(ev.tenantId(), shard)) return false;
            TenantAdmission.Decision admission = admit(ev);
            if (!admission.admitted()) return false;
            TenantAdmission.Permit permit = admission.permit();
            try {
                RuleEngine.Submission submission = lifecycle.engineFor(ev.tenantId(), shard).submit(ev, false);
                if (!submission.accepted()) {
                    tenantAdmission.rollback(permit);
                    return false;
                }
                submission.completion().whenComplete((ignored, failure) -> tenantAdmission.release(permit));
                return true;
            } catch (RuntimeException recoveryFailure) {
                tenantAdmission.rollback(permit);
                return false;
            }
        } finally {
            lifecycle.readLock().unlock();
        }
    }

    public List<Alert> recentAlerts() {
        return sink.recent(store.tenant());
    }

    public Map<String, Object> stats() {
        String tenant = store.tenant();
        List<RuleEngine> tenantEngines = lifecycle.tenantEngines(tenant);
        long eventCount = tenantEngines.stream().mapToLong(RuleEngine::eventCount).sum();
        long alertCount = tenantEngines.stream().mapToLong(RuleEngine::alertCount).sum();
        long dropCount = tenantEngines.stream().mapToLong(RuleEngine::dropCount).sum();
        // Each engine counts the suppression decisions it made itself, so this
        // sum is the tenant's real number for any shard count.
        long suppressedCount = tenantEngines.stream().mapToLong(RuleEngine::suppressedCount).sum();
        double queueLoad = tenantEngines.stream().mapToDouble(RuleEngine::queueLoad).max().orElse(0.0);
        List<Map<String, Object>> ruleStats = tenantEngines.stream()
                .flatMap(engine -> engine.ruleStats().stream())
                .toList();
        Map<String, Object> m = new LinkedHashMap<>();
        // Stats are polled frequently and must not allocate the complete
        // tenant rule catalogue just to calculate a count.
        m.put("rules", store.count(tenant));
        m.put("eventCount", eventCount);
        m.put("alertCount", alertCount);
        m.put("dropCount", dropCount);
        m.put("suppressedCount", suppressedCount);
        m.put("queueLoad", queueLoad);
        m.put("ruleStats", ruleStats);
        // Isolation is counted per engine key, so a tenant view must not add up
        // another tenant's skipped rules into its own number.
        m.put("isolatedRules", lifecycle.isolatedCount(tenant));
        m.put("routingMismatchWindows", tenantEngines.stream()
                .mapToLong(RuleEngine::routingMismatchWindows).sum());
        m.put("instance", instanceId);
        m.put("assignedPartitions", state.assignedPartitions());
        m.put("pendingEvents", stateStore.pendingCount(tenant));

        m.put("stateRecovery", lifecycle.recoveryStats(tenant));
        m.put("stateSnapshots", state.snapshotStats());
        m.put("runtimeRole", lifecycle.runtimeRole());
        m.put("detectionWorkerEnabled", lifecycle.workerRole());
        m.put("cachedTenantEngines", lifecycle.cachedTenantEngines());
        m.put("tenantAdmission", tenantAdmission.stats(tenant));
        m.put("tenantAdmissionRejections", tenantAdmission.rejectionStats(tenant));
        return m;
    }

    private TenantAdmission.Decision admit(SecurityEvent event) {
        tenantAdmission.configure(tenantMaxEventsPerSecond, tenantRateBurst,
                tenantMaxPendingBytes, tenantMaxActiveEntities, tenantEntityIdleTtlMs);
        return tenantAdmission.tryAcquire(event, TenantAdmission.estimateBytes(event));
    }
}
