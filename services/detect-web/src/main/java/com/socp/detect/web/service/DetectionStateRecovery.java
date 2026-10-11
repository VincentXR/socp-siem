package com.socp.detect.web.service;

import com.socp.detect.web.persistence.store.DetectionStateStore;
import com.socp.detect.web.persistence.store.DetectionStateOwnership;
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
    private final Map<String, AtomicLong> snapshotCounters = new ConcurrentHashMap<>();
    private final Map<String, Map<Integer, Long>> snapshotOffsets = new ConcurrentHashMap<>();
    private final Map<String, DetectionStateOwnership.Lease> stateLeases = new ConcurrentHashMap<>();
    private final Map<String, Object> stateLeaseLocks = new ConcurrentHashMap<>();
    private final Set<Integer> revokedPartitions = ConcurrentHashMap.newKeySet();
    private final AtomicReference<Set<Integer>> assignedPartitions = new AtomicReference<>(Set.of());

    @Value("${socp.detect.input-topic:${socp.kafka.topic:socp-events}}")
    private String inputTopic = "socp-events";

    @Value("${socp.detect.state.snapshot-every-events:500}")
    private long snapshotEveryEvents = 500L;

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
        revokedPartitions.removeAll(partitions);
        assignedPartitions.set(partitions);
    }

    void clearAssignment() {
        revokedPartitions.clear();
        assignedPartitions.set(Set.of());
    }

    /** Release only the fencing rows owned by this process on partition revoke. */
    void releaseForPartitions(Set<Integer> partitions) {
        if (partitions == null || partitions.isEmpty()) return;
        Set<Integer> revoked = Set.copyOf(partitions);
        revokedPartitions.addAll(revoked);
        assignedPartitions.updateAndGet(current -> {
            Set<Integer> retained = new java.util.HashSet<>(current);
            retained.removeAll(revoked);
            return Set.copyOf(retained);
        });
        for (String key : stateLeases.keySet()) {
            Object lock = stateLeaseLocks.get(key);
            if (lock == null) continue;
            synchronized (lock) {
                DetectionStateOwnership.Lease lease = stateLeases.get(key);
                if (lease != null && revoked.contains(lease.partition())
                        && stateLeases.remove(key, lease)) {
                    stateOwnership.release(lease);
                }
            }
        }
    }

    void releaseAllStateLeases() {
        for (String key : stateLeases.keySet()) {
            Object lock = stateLeaseLocks.get(key);
            if (lock == null) continue;
            synchronized (lock) {
                DetectionStateOwnership.Lease lease = stateLeases.remove(key);
                if (lease != null) stateOwnership.release(lease);
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
        Object lock = stateLeaseLocks.computeIfAbsent(key, ignored -> new Object());
        synchronized (lock) {
            if (revokedPartitions.contains(partition)) {
                throw new DetectionStateOwnership.StaleStateOwnerException(
                        "Kafka partition is no longer assigned: " + partition);
            }
            DetectionStateOwnership.Lease current = stateLeases.get(key);
            if (current != null) {
                try {
                    stateOwnership.assertCurrent(current);
                    return current;
                } catch (DetectionStateOwnership.StaleStateOwnerException stale) {
                    stateLeases.remove(key, current);
                }
            }
            DetectionStateOwnership.Lease acquired = stateOwnership.acquire(
                    inputTopic, partition, resolvedShard);
            stateLeases.put(key, acquired);
            return acquired;
        }
    }

    Runnable durableGuardFor(int partition, int shard) {
        DetectionStateOwnership.Lease lease = stateLeaseFor(partition, shard);
        return lease == null ? () -> { } : () -> stateOwnership.assertCurrent(lease);
    }

    void acquireStateLeases(Set<Integer> partitions) {
        if (partitions == null) return;
        for (Integer partition : partitions) {
            if (partition == null || partition < 0) continue;
            for (int shard = 0; shard < effectiveShardCount(); shard++) {
                stateLeaseFor(partition, shard);
            }
        }
    }

    void releaseUnassignedStateLeases(Set<Integer> partitions) {
        for (String key : stateLeases.keySet()) {
            Object lock = stateLeaseLocks.get(key);
            if (lock == null) continue;
            synchronized (lock) {
                DetectionStateOwnership.Lease lease = stateLeases.get(key);
                boolean retained = lease != null && partitions != null
                        && partitions.contains(lease.partition())
                        && lease.shard() < effectiveShardCount();
                if (!retained && lease != null && stateLeases.remove(key, lease)) {
                    stateOwnership.release(lease);
                }
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

    void restoreState(String tenant, RuleEngine replacement, Set<Integer> partitions, int shard) {
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
                    return;
                }
            }
            // A missing, incompatible, or corrupt rule snapshot must never be
            // paired with a tail replay. Start from the durable event window
            // instead so every stateful rule sees the same history boundary.
            snapshotOffsets.remove(engineKey(tenant, normalizeShard(shard)));
        }
        if (partitions == null || partitions.isEmpty()) {
            stateStore.replayRecentForTenant(tenant, configuredRecoveryWindow(), events -> replacement.restore(
                    events.stream().filter(event -> shardFor(event) == normalizeShard(shard)).toList()));
        } else {
            stateStore.replayRecentForPartitions(
                    partitions, configuredRecoveryWindow(), events -> {
                        List<SecurityEvent> owned = events.stream()
                                .filter(event -> tenant.equals(event.tenantId()))
                                .filter(event -> shardFor(event) == normalizeShard(shard))
                                .toList();
                        if (!owned.isEmpty()) replacement.restore(owned);
                    });
        }
    }

    Set<Integer> assignedPartitions() {
        return assignedPartitions.get();
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
        if (snapshotStore == null || event == null || engine == null) return;
        String tenant = event.requireTenantId();
        long every = Math.max(1L, snapshotEveryEvents);
        int shard = shardFor(event);
        String counterKey = engineKey(tenant, shard);
        long count = snapshotCounters.computeIfAbsent(counterKey, ignored -> new AtomicLong()).incrementAndGet();
        Map<Integer, Long> offsets = snapshotOffsets.computeIfAbsent(counterKey,
                ignored -> new ConcurrentHashMap<>());
        if (partition != null && offset != null && partition >= 0 && offset >= 0) {
            offsets.merge(partition, offset, Math::max);
        }
        if (count % every != 0) return;
        try {
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
        } catch (RuntimeException failure) {
            // Checkpoints are an optimization. The durable journal remains the
            // source of truth when a snapshot write is temporarily unavailable.
            org.slf4j.LoggerFactory.getLogger(DetectionStateRecovery.class).warn(
                    "Detection state snapshot deferred tenant={}: {}", tenant, failure.getMessage());
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
        if (revokedPartitions.contains(partition) || !assignedPartitions.get().contains(partition)) {
            throw new DetectionStateOwnership.StaleStateOwnerException(
                    "Kafka partition is no longer assigned: " + partition);
        }
        durableGuardFor(partition, shardFor(event)).run();
    }

    void clearCheckpoints() {
        snapshotCounters.clear();
        snapshotOffsets.clear();
    }

    void forgetCheckpoint(String key) {
        snapshotCounters.remove(key);
        snapshotOffsets.remove(key);
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
                "everyEvents", Math.max(1L, snapshotEveryEvents), "shards", effectiveShardCount());
    }

    void describeReplay(Map<String, Object> stats) {
        stats.put("store", stateStore.getClass().getSimpleName());
        String window = stateStore.recoveryWindow();
        stats.put("replayWindow", window == null ? "unknown" : window);
    }
}
