package com.socp.detect.web.service;

import com.socp.detect.web.persistence.store.DetectionStateStore;
import com.socp.detect.web.persistence.store.DetectionStateOwnership;
import com.socp.platform.tenant.context.TenantContext;
import com.socp.rule.engine.RuleEngine;
import com.socp.rule.model.SecurityEvent;
import com.socp.rule.partition.DetectionRoutingKey;
import com.socp.rule.state.DetectionStateSnapshot;
import com.socp.rule.state.DetectionStateSnapshotStore;
import com.socp.rule.state.StateRoutingKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/**
 * Durable recovery boundary: journal replay, coherent checkpoints and Kafka state-unit fencing.
 * Engine replacement and admission synchronization belong to {@link DetectionEngineLifecycle}.
 */
@Component
public class DetectionStateRecovery {
    private final DetectionStateStore stateStore;
    private final DetectionStateSnapshotStore snapshotStore;
    private final DetectionStateOwnership stateOwnership;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.socp.detect.web.metrics.DetectionPerformanceMetrics performanceMetrics;
    private final Map<String, Long> snapshotAttempts = new ConcurrentHashMap<>();
    private java.util.function.LongSupplier nanoTime = System::nanoTime;
    private final Map<String, AtomicLong> snapshotCounters = new ConcurrentHashMap<>();
    private final Map<String, Map<Integer, Long>> snapshotOffsets = new ConcurrentHashMap<>();
    private final Map<String, DetectionStateOwnership.Lease> stateLeases = new ConcurrentHashMap<>();
    private final Map<String, ReentrantLock> stateLeaseLocks = new ConcurrentHashMap<>();
    private final Set<Integer> revokedPartitions = ConcurrentHashMap.newKeySet();
    record RecoveryAssignment(Set<Integer> partitions) { }
    private final Object assignmentBoundary = new Object();
    private final AtomicReference<RecoveryAssignment> assignment =
            new AtomicReference<>(new RecoveryAssignment(Set.of()));

    @Value("${socp.detect.input-topic:${socp.kafka.topic:socp-events}}")
    private String inputTopic = "socp-events";

    @Value("${socp.detect.state.snapshot-every-events:500}")
    private long snapshotEveryEvents = 500L;

    @Value("${socp.detect.state.snapshot-min-interval-ms:5000}")
    private long snapshotMinIntervalMs = 5000L;

    /** Number of independent in-process state shards. One preserves the
     * historical single-engine behaviour; larger values route by the same
     * tenant/entity tuple used as the Kafka key. */
    @Value("${socp.detect.state.shards:1}")
    private int stateShardCount = 1;

    /** Replay boundary shared with the journal and pending-work recovery. */
    @Value("${socp.detect.state.retention}")
    private Duration recoveryWindow;

    public DetectionStateRecovery(DetectionStateStore stateStore,
                                  DetectionStateSnapshotStore snapshotStore,
                                  DetectionStateOwnership stateOwnership) {
        this.stateStore = stateStore;
        this.snapshotStore = snapshotStore;
        this.stateOwnership = stateOwnership == null ? DetectionStateOwnership.noop() : stateOwnership;
    }

    String inputTopic() { return inputTopic; }

    boolean snapshotsEnabled() { return snapshotStore != null; }

    void assign(Set<Integer> partitions) {
        synchronized (assignmentBoundary) {
            revokedPartitions.removeAll(partitions);
            assignment.set(new RecoveryAssignment(Set.copyOf(partitions)));
        }
    }

    void clearAssignment() {
        synchronized (assignmentBoundary) {
            revokedPartitions.clear();
            assignment.set(new RecoveryAssignment(Set.of()));
        }
    }

    /** Release only the fencing rows owned by this process on partition revoke. */
    void releaseForPartitions(Set<Integer> partitions) {
        if (partitions == null || partitions.isEmpty()) return;
        Set<Integer> revoked = Set.copyOf(partitions);
        synchronized (assignmentBoundary) {
            revokedPartitions.addAll(revoked);
            Set<Integer> retained = new java.util.HashSet<>(assignment.get().partitions());
            retained.removeAll(revoked);
            assignment.set(new RecoveryAssignment(Set.copyOf(retained)));
        }
        for (String key : stateLeases.keySet()) {
            ReentrantLock lock = stateLeaseLocks.get(key);
            if (lock == null) continue;
            lock.lock();
            try {
                DetectionStateOwnership.Lease lease = stateLeases.get(key);
                if (lease != null && revoked.contains(lease.partition())
                        && stateLeases.remove(key, lease)) {
                    stateOwnership.release(lease);
                }
            } finally {
                lock.unlock();
            }
        }
    }

    void releaseAllStateLeases() {
        for (String key : stateLeases.keySet()) {
            ReentrantLock lock = stateLeaseLocks.get(key);
            if (lock == null) continue;
            lock.lock();
            try {
                DetectionStateOwnership.Lease lease = stateLeases.remove(key);
                if (lease != null) stateOwnership.release(lease);
            } finally {
                lock.unlock();
            }
        }
    }

    int normalizeShard(int shard) {
        return Math.floorMod(shard, effectiveShardCount());
    }

    static String resolveTenant(String tenant) {
        return tenant == null || tenant.isBlank() ? "default" : tenant;
    }

    int effectiveShardCount() {
        return Math.max(1, Math.min(256, stateShardCount));
    }

    private DetectionStateOwnership.Lease stateLeaseFor(int partition, int shard) {
        if (partition < 0) return null;
        if (revokedPartitions.contains(partition)) {
            throw new DetectionStateOwnership.StaleStateOwnerException(
                    "Kafka partition is no longer assigned: " + partition);
        }
        int resolvedShard = normalizeShard(shard);
        String key = DetectionStateOwnership.unitKey(inputTopic, partition, resolvedShard);
        ReentrantLock lock = stateLeaseLocks.computeIfAbsent(key, ignored -> new ReentrantLock());
        lock.lock();
        try {
            if (revokedPartitions.contains(partition)) {
                throw new DetectionStateOwnership.StaleStateOwnerException(
                        "Kafka partition is no longer assigned: " + partition);
            }
            DetectionStateOwnership.Lease current = stateLeases.get(key);
            // A cached lease is an epoch token, not cached authorization.
            // Every durable boundary validates it against the database.
            if (current != null) return current;
            DetectionStateOwnership.Lease acquired = stateOwnership.acquire(
                    inputTopic, partition, resolvedShard);
            stateLeases.put(key, acquired);
            return acquired;
        } finally {
            lock.unlock();
        }
    }

    Runnable durableGuardFor(int partition, int shard) {
        DetectionStateOwnership.Lease lease = stateLeaseFor(partition, shard);
        return lease == null ? new com.socp.rule.engine.OrderedCommitGuard("", () -> { })
                : new com.socp.rule.engine.OrderedCommitGuard(lease.unitKey(), new LeaseGuard(lease));
    }

    private final class LeaseGuard implements com.socp.rule.engine.OrderedCommitGuard.BatchCheck {
        private final DetectionStateOwnership.Lease lease;
        private LeaseGuard(DetectionStateOwnership.Lease lease) { this.lease = lease; }
        @Override public Object batchOwner() { return DetectionStateRecovery.this; }
        @Override public void run() {
            try { stateOwnership.assertCurrent(lease); }
            catch (DetectionStateOwnership.StaleStateOwnerException stale) {
                stateLeases.remove(lease.unitKey(), lease);
                throw stale;
            }
        }
        @Override public void runBatch(java.util.List<com.socp.rule.engine.OrderedCommitGuard.BatchCheck> checks) {
            var leases = checks.stream().map(check -> {
                if (!(check instanceof LeaseGuard guard) || guard.batchOwner() != batchOwner()) {
                    throw new IllegalArgumentException("incompatible detection state authority");
                }
                return guard.lease;
            }).toList();
            try { stateOwnership.assertCurrentBatch(leases); }
            catch (DetectionStateOwnership.StaleStateOwnerException stale) {
                // A failed batch has no durable results. Drop its cached tokens
                // so the ordered retry reacquires authoritative epochs.
                leases.forEach(token -> stateLeases.remove(token.unitKey(), token));
                throw stale;
            }
        }
    }

    void acquireStateLeases(Set<Integer> partitions) {
        if (partitions == null) return;
        for (Integer partition : partitions) {
            if (partition == null || partition < 0) continue;
            for (int shard = 0; shard < effectiveShardCount(); shard++) {
                durableGuardFor(partition, shard).run();
            }
        }
    }

    void releaseUnassignedStateLeases(Set<Integer> partitions) {
        for (String key : stateLeases.keySet()) {
            ReentrantLock lock = stateLeaseLocks.get(key);
            if (lock == null) continue;
            lock.lock();
            try {
                DetectionStateOwnership.Lease lease = stateLeases.get(key);
                boolean retained = lease != null && partitions != null
                        && partitions.contains(lease.partition())
                        && lease.shard() < effectiveShardCount();
                if (!retained && lease != null && stateLeases.remove(key, lease)) {
                    stateOwnership.release(lease);
                }
            } finally {
                lock.unlock();
            }
        }
    }

    static String engineKey(String tenant, int shard) {
        return tenant + "\u0000shard-" + shard;
    }

    int shardFor(SecurityEvent event) {
        if (effectiveShardCount() == 1) return 0;
        String tenant = event.requireTenantId();
        String field = DetectionRoutingKey.field(event.source(), event.host(), event.fields());
        String value = DetectionRoutingKey.value(event.source(), event.host(), event.fields());
        return new StateRoutingKey(tenant, field, value).shard(effectiveShardCount());
    }

    /** Reverse of {@link #engineKey}: the tenant part of an engine key. */
    static String tenantOfEngineKey(String key) {
        if (key == null) return null;
        int marker = key.lastIndexOf("shard-");
        return marker <= 1 ? null : key.substring(0, marker - 1);
    }

    /** Whether a compatible checkpoint or owned journal history was restored. */
    boolean restoreState(String tenant, RuleEngine replacement, Set<Integer> partitions, int shard) {
        if (snapshotStore != null && stateStore.supportsCheckpointReplay()) {
            List<String> statefulRuleIds = replacement.statefulRuleIds();
            Map<String, RuleEngine.RuleState> snapshots = new LinkedHashMap<>();
            Map<Integer, Long> checkpointOffsets = new LinkedHashMap<>();
            java.time.Instant checkpoint = null;
            java.time.Instant referenceTimestamp = null;
            Map<Integer, Long> referenceOffsets = null;
            Map<Integer, Long> referenceOwnerEpochs = null;
            boolean coherent = true;
            boolean complete = !statefulRuleIds.isEmpty();
            for (String ruleId : statefulRuleIds) {
                var latest = snapshotStore.latest(tenant, ruleId, normalizeShard(shard));
                if (latest.isEmpty()) {
                    complete = false;
                    continue;
                }
                var snapshot = latest.get();
                snapshots.put(ruleId, new RuleEngine.RuleState(ruleId,
                        snapshot.ruleVersion(), snapshot.serializedState()));
                if (checkpoint == null || snapshot.snapshotTimestamp().isBefore(checkpoint)) {
                    checkpoint = snapshot.snapshotTimestamp();
                }
                // Every rule row in a generation must describe the same state
                // boundary. Mixed timestamps or vectors indicate an older
                // interrupted write; replay the durable window instead of
                // double-applying the newer rows.
                if (referenceOffsets == null) referenceOffsets = snapshot.partitionOffsets();
                else if (!referenceOffsets.equals(snapshot.partitionOffsets())) coherent = false;
                if (referenceOwnerEpochs == null) referenceOwnerEpochs = snapshot.partitionOwnerEpochs();
                else if (!referenceOwnerEpochs.equals(snapshot.partitionOwnerEpochs())) coherent = false;
                if (snapshot.inputTopic() != null && !inputTopic.equals(snapshot.inputTopic())) {
                    coherent = false;
                }
                if (referenceTimestamp == null) referenceTimestamp = snapshot.snapshotTimestamp();
                else if (!referenceTimestamp.equals(snapshot.snapshotTimestamp())) {
                    coherent = false;
                }
            }
            // A snapshot containing revoked partitions cannot be split back into
            // per-partition state. Rebuild the owned window on a narrower assignment.
            if (partitions != null && !partitions.isEmpty()
                    && (referenceOffsets == null || referenceOffsets.isEmpty()
                        || !partitions.containsAll(referenceOffsets.keySet()))) coherent = false;
            if (complete && coherent && checkpoint != null) {
                if (referenceOffsets != null) checkpointOffsets.putAll(referenceOffsets);
                List<String> restored = replacement.restoreStates(snapshots);
                if (restored.size() == statefulRuleIds.size()) {
                    String key = engineKey(tenant, normalizeShard(shard));
                    snapshotOffsets.put(key, new ConcurrentHashMap<>(checkpointOffsets));
                    if (checkpointOffsets.isEmpty()) {
                        // Legacy snapshots have no vector. Keep their original
                        // timestamp-tail behaviour until the next checkpoint.
                        stateStore.replayCompletedAfter(tenant, checkpoint, partitions,
                                events -> replacement.restore(events.stream()
                                        .filter(event -> tenant.equals(event.tenantId()))
                                        .filter(event -> shardFor(event) == normalizeShard(shard))
                                        .toList()));
                    } else {
                        stateStore.replayCompletedAfter(tenant, checkpoint, partitions,
                                checkpointOffsets,
                                events -> replacement.restore(events.stream()
                                        .filter(event -> tenant.equals(event.tenantId()))
                                        .filter(event -> shardFor(event) == normalizeShard(shard))
                                        .toList()));
                    }
                    org.slf4j.LoggerFactory.getLogger(DetectionStateRecovery.class).info(
                            "Detection state restored from snapshots tenant={} rules={} checkpoint={} partitions={}",
                            tenant, restored.size(), checkpoint, checkpointOffsets.size());
                    return true;
                }
            }
            // A missing, incompatible, or corrupt rule snapshot must never be
            // paired with a tail replay. Start from the durable event window
            // instead so every stateful rule sees the same history boundary.
            snapshotOffsets.remove(engineKey(tenant, normalizeShard(shard)));
        }
        java.util.concurrent.atomic.AtomicBoolean restored = new java.util.concurrent.atomic.AtomicBoolean();
        if (partitions == null || partitions.isEmpty()) {
            stateStore.replayRecentForTenant(tenant, configuredRecoveryWindow(), events -> {
                List<SecurityEvent> owned = events.stream()
                        .filter(event -> shardFor(event) == normalizeShard(shard)).toList();
                if (!owned.isEmpty()) {
                    replacement.restore(owned);
                    restored.set(true);
                }
            });
        } else {
            try (var scope = com.socp.platform.tenant.context.TenantContext.open(tenant)) {
                stateStore.replayRecentForPartitions(
                    partitions, configuredRecoveryWindow(), events -> {
                        List<SecurityEvent> owned = events.stream()
                                .filter(event -> tenant.equals(event.tenantId()))
                                .filter(event -> shardFor(event) == normalizeShard(shard))
                                .toList();
                        if (!owned.isEmpty()) {
                            replacement.restore(owned);
                            restored.set(true);
                        }
                    });
            }
        }
        return restored.get();
    }

    Set<Integer> assignedPartitions() { return assignment.get().partitions(); }

    RecoveryAssignment recoveryAssignment() { return assignment.get(); }

    boolean isCurrentAssignment(RecoveryAssignment captured) { return assignment.get() == captured; }

    /** Publish a restored engine atomically with respect to assignment changes. */
    void publishForAssignment(RecoveryAssignment captured, Runnable publish) {
        synchronized (assignmentBoundary) {
            if (assignment.get() != captured || Thread.currentThread().isInterrupted()) {
                throw new DetectionStateOwnership.StaleStateOwnerException("assignment changed during state restoration");
            }
            publish.run();
        }
    }

    /**
     * Spring supplies this from the single retention property. Compatibility
     * constructors used by unit tests intentionally fail closed to an empty
     * replay window until configuration is injected.
     */
    private Duration configuredRecoveryWindow() {
        return recoveryWindow == null ? Duration.ZERO : recoveryWindow;
    }

    void recordDurablePosition(SecurityEvent event, Integer partition, Long offset) {
        if (event == null || partition == null || offset == null || partition < 0 || offset < 0) return;
        String key = engineKey(event.requireTenantId(), shardFor(event));
        snapshotOffsets.computeIfAbsent(key, ignored -> new ConcurrentHashMap<>())
                .merge(partition, offset, Math::max);
    }

    /** Capture from a known engine instance; used by its worker callback during a hot swap. */
    void snapshotAfterDurable(RuleEngine engine, SecurityEvent event,
                                      Integer partition, Long offset) {
        snapshotAfterDurable(engine, event, partition, offset, 1);
    }

    /** Batch state and its scalar/vector watermarks must all describe the last durable event. */
    void snapshotAfterDurable(RuleEngine engine, SecurityEvent event,
                              Integer partition, Long offset, int processedEvents) {
        if (processedEvents < 1 || processedEvents > RuleEngine.MAX_BATCH_EVENTS) {
            throw new IllegalArgumentException("invalid checkpoint batch size");
        }
        if (snapshotStore == null || event == null || engine == null) return;
        String tenant = event.requireTenantId();
        long every = Math.max(1L, snapshotEveryEvents);
        int shard = shardFor(event);
        String counterKey = engineKey(tenant, shard);
        long before = snapshotCounters.computeIfAbsent(counterKey, ignored -> new AtomicLong()).getAndAdd(processedEvents);
        long count = before + processedEvents;
        Map<Integer, Long> offsets = snapshotOffsets.computeIfAbsent(counterKey,
                ignored -> new ConcurrentHashMap<>());
        if (partition != null && offset != null && partition >= 0 && offset >= 0) {
            offsets.merge(partition, offset, Math::max);
        }
        if (before / every == count / every) return;
        long now = nanoTime.getAsLong();
        Long previous = snapshotAttempts.get(counterKey);
        long interval = java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(Math.max(0L, snapshotMinIntervalMs));
        if (previous != null && now - previous < interval) return;
        // Bound failed attempts too. Journal durability is independent of checkpoints.
        snapshotAttempts.put(counterKey, now);
        long checkpointStarted = System.nanoTime();
        boolean acknowledged = false;
        try (var scope = com.socp.platform.tenant.context.TenantContext.open(tenant)) {
            // Snapshot writes are also a durable side effect. Do not let a
            // revoked worker overwrite the replacement owner's checkpoint.
            durableGuardFor(partition == null ? -1 : partition, shard).run();
            List<DetectionStateSnapshot> snapshots = engine.captureStateSnapshot(states -> {
                java.time.Instant timestamp = java.time.Instant.now();
                Map<Integer, Long> checkpoint = Map.copyOf(offsets);
                Map<Integer, Long> ownerEpochs = ownerEpochsFor(checkpoint.keySet(), shard);
                long processedOffset = offset == null ? -1L : offset;
                return states.entrySet().stream()
                        .map(entry -> new DetectionStateSnapshot(entry.getKey(), entry.getValue().version(),
                                tenant, shard, processedOffset, entry.getValue().serializedState(), timestamp,
                                checkpoint, ownerEpochs, inputTopic))
                        .toList();
            });
            if (snapshotStore.supportsAtomicBatch()) snapshotStore.saveAll(snapshots);
            else snapshots.forEach(snapshotStore::save);
            acknowledged = true;
        } catch (RuntimeException failure) {
            // Checkpoints are an optimization. The durable journal remains the
            // source of truth when a snapshot write is temporarily unavailable.
            org.slf4j.LoggerFactory.getLogger(DetectionStateRecovery.class).warn(
                    "Detection state snapshot deferred tenant={}: {}", tenant, failure.getMessage());
        } finally {
            com.socp.rule.engine.RuleProcessingObserver.notifyOperation(performanceMetrics,
                    com.socp.rule.engine.RuleProcessingObserver.Operation.CHECKPOINT,
                    1, System.nanoTime() - checkpointStarted, acknowledged);
        }
    }

    private Map<Integer, Long> ownerEpochsFor(Set<Integer> partitions, int shard) {
        if (partitions == null || partitions.isEmpty()) return Map.of();
        Map<Integer, Long> epochs = new LinkedHashMap<>();
        for (Integer partition : partitions) {
            if (partition == null || partition < 0) continue;
            DetectionStateOwnership.Lease lease = stateLeaseFor(partition, shard);
            if (lease == null) continue;
            stateOwnership.assertCurrent(lease);
            epochs.put(partition, lease.fencingEpoch());
        }
        return Map.copyOf(epochs);
    }

    void assertCurrentOwner(SecurityEvent event, Integer partition) {
        if (event == null || partition == null || partition < 0) return;
        if (revokedPartitions.contains(partition) || !assignedPartitions().contains(partition)) {
            throw new DetectionStateOwnership.StaleStateOwnerException(
                    "Kafka partition is no longer assigned: " + partition);
        }
        durableGuardFor(partition, shardFor(event)).run();
    }

    void clearCheckpoints() {
        snapshotCounters.clear();
        snapshotAttempts.clear();
        snapshotOffsets.clear();
    }

    void forgetCheckpoint(String key) {
        snapshotCounters.remove(key);
        snapshotAttempts.remove(key);
        snapshotOffsets.remove(key);
    }

    boolean restoreTenants(Set<Integer> partitions, Consumer<String> restoreTenant) {
        return stateStore.replayRecoveryTenants(partitions, configuredRecoveryWindow(), restoreTenant);
    }

    void replayRecent(Set<Integer> partitions, Consumer<List<SecurityEvent>> restoreBatch) {
        if (partitions == null || partitions.isEmpty()) {
            stateStore.replayRecent(configuredRecoveryWindow(), restoreBatch);
        } else {
            stateStore.replayRecentForPartitions(partitions, configuredRecoveryWindow(), restoreBatch);
        }
    }

    Map<String, Object> snapshotStats() {
        return Map.of("store", snapshotStore == null ? "disabled" : snapshotStore.getClass().getSimpleName(),
                "everyEvents", Math.max(1L, snapshotEveryEvents),
                "minIntervalMs", Math.max(0L, snapshotMinIntervalMs), "shards", effectiveShardCount());
    }

    void describeReplay(Map<String, Object> stats) {
        stats.put("store", stateStore.getClass().getSimpleName());
        String window = stateStore.recoveryWindow();
        stats.put("replayWindow", window == null ? "unknown" : window);
    }
}
