package com.socp.detect.web.service;

import com.socp.detect.web.engine.AlertForwarder;
import com.socp.detect.web.engine.RecentAlertSink;
import com.socp.detect.web.engine.TenantAdmission;
import com.socp.detect.web.metrics.DetectionPerformanceMetrics;
import com.socp.detect.web.persistence.store.DetectionStateStore;
import com.socp.detect.web.persistence.store.DetectionEventClaim;
import com.socp.detect.web.persistence.store.DetectionStateOwnership;
import com.socp.detect.web.persistence.store.InMemoryDetectionStateStore;
import com.socp.detect.web.persistence.store.RuleSpecStore;
import com.socp.rule.engine.RuleEngine;
import com.socp.rule.engine.RuleProcessingObserver;
import com.socp.rule.engine.Suppressor;
import com.socp.rule.model.Alert;
import com.socp.rule.model.SecurityEvent;
import com.socp.rule.partition.DetectionRoutingKey;
import com.socp.rule.state.DetectionStateSnapshot;
import com.socp.rule.state.DetectionStateSnapshotStore;
import com.socp.rule.state.StateRoutingKey;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * DETECT 检测引擎服务：把规则存储（RuleSpec）装配成可运行的 {@link RuleEngine}，
 * 提供规则热更新（reload）、事件摄取（背压语义）、告警查询与运行统计。
 *
 * <p>集群无关实现（内存规则存储 + 内存告警出口）；生产化后规则落 PG、告警经 Kafka
 * 交给 GASModel 窗口聚合，再落 ALERT t_alarm，本服务契约保持不变。
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
    private final AlertForwarder forwarder;
    private final Suppressor suppressor = new Suppressor(Duration.ofMinutes(5));
    private final Map<String, RuleEngine> engines = new ConcurrentHashMap<>();
    private final Map<String, Long> engineLastAccess = new ConcurrentHashMap<>();
    private final DetectionRuleService ruleService;
    private final DetectionEngineFactory engineFactory;
    private final DetectionStateStore stateStore;
    private final DetectionStateSnapshotStore snapshotStore;
    private final Map<String, AtomicLong> snapshotCounters = new ConcurrentHashMap<>();
    /** State rebuilds that found no journaled history and warmed an empty engine. */
    private final AtomicLong warmedWithoutHistory = new AtomicLong();
    /** Last durable Kafka position included in each tenant/shard snapshot. */
    private final Map<String, Map<Integer, Long>> snapshotOffsets = new ConcurrentHashMap<>();
    /** Ownership leases are keyed by the actual Kafka state unit, not tenant. */
    private final DetectionStateOwnership stateOwnership;
    private final Map<String, DetectionStateOwnership.Lease> stateLeases = new ConcurrentHashMap<>();
    private final Map<String, Object> stateLeaseLocks = new ConcurrentHashMap<>();
    /** Per-tenant rate, pending-byte, and active-entity admission budgets. */
    private final TenantAdmission tenantAdmission = new TenantAdmission();
    /** Revoke wins over a concurrent pre-admission lease lookup. */
    private final Set<Integer> revokedPartitions = ConcurrentHashMap.newKeySet();
    private final AtomicReference<Set<Integer>> assignedPartitions = new AtomicReference<>(Set.of());
    /** Starts READY for source-compatible unit callers; Spring invokes start before traffic. */
    private final AtomicReference<RecoveryStatus> recoveryStatus =
            new AtomicReference<>(RecoveryStatus.READY);
    /**
     * Recovery is scoped. The process scope covers startup and a full Kafka
     * assignment rebuild, which really does replace every engine. A tenant hot
     * reload only marks the engine keys it owns, so one tenant's rule edit can
     * not open a rejection window for every other tenant in the process.
     */
    private final Map<String, RecoveryStatus> engineRecovery = new ConcurrentHashMap<>();
    /** Rebuilds that failed while the previous engines stayed in service. */
    private final Map<String, String> pendingRebuilds = new ConcurrentHashMap<>();
    private final AtomicLong rebuildRetries = new AtomicLong();
    private volatile String recoveryFailure;
    private final ReentrantReadWriteLock engineLifecycle = new ReentrantReadWriteLock(true);

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

    @Value("${socp.detect.input-topic:${socp.kafka.topic:socp-events}}")
    private String inputTopic = "socp-events";

    /** Management-only processes must not restore or rebuild detection state. */
    @Value("${socp.detect.runtime-role:all}")
    private String runtimeRole = "all";

    @Value("${socp.detect.engine.idle-ttl-ms:1800000}")
    private long engineIdleTtlMs = 30 * 60 * 1000L;

    @Value("${socp.detect.engine.max-tenants:1000}")
    private int maxTenantEngines = 1000;

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

    /**
     * How long a hot reload waits for the live engine to finish accepted work
     * before it reads the Journal. A rebuild that cannot drain in this budget is
     * abandoned with the live engines still serving, instead of swapping in state
     * that is missing an in-flight completion.
     */
    @Value("${socp.detect.reload.drain-timeout-ms:30000}")
    private long reloadDrainTimeoutMs = 30_000L;

    /**
     * Operator label for this worker. Recovery and isolation counters are engine
     * state inside one process, so a stats reader must know which replica it is
     * looking at.
     */
    @Value("${socp.detect.instance-id:unknown}")
    private String instanceId = "unknown";

    public DetectEngineService(RuleSpecStore store, RecentAlertSink sink, AlertForwarder forwarder,
                               RuleChangePublisher rulePublisher, DetectionStateStore stateStore,
                               DetectionPerformanceMetrics performanceMetrics,
                               DetectionStateSnapshotStore snapshotStore) {
        this(store, sink, forwarder, rulePublisher, stateStore, performanceMetrics,
                snapshotStore, DetectionStateOwnership.noop());
    }

    @org.springframework.beans.factory.annotation.Autowired
    public DetectEngineService(RuleSpecStore store, RecentAlertSink sink, AlertForwarder forwarder,
                               RuleChangePublisher rulePublisher, DetectionStateStore stateStore,
                               DetectionPerformanceMetrics performanceMetrics,
                               DetectionStateSnapshotStore snapshotStore,
                               DetectionStateOwnership stateOwnership) {
        this.store = store;
        this.sink = sink;
        this.forwarder = forwarder;
        this.ruleService = new DetectionRuleService(store, rulePublisher);
        this.stateStore = stateStore;
        this.snapshotStore = snapshotStore;
        this.stateOwnership = stateOwnership == null ? DetectionStateOwnership.noop() : stateOwnership;
        this.engineFactory = new DetectionEngineFactory(store, sink, suppressor, performanceMetrics);
    }

    /** Source-compatible constructor for callers that do not configure snapshots. */
    public DetectEngineService(RuleSpecStore store, RecentAlertSink sink, AlertForwarder forwarder,
                               RuleChangePublisher rulePublisher, DetectionStateStore stateStore,
                               DetectionPerformanceMetrics performanceMetrics) {
        this(store, sink, forwarder, rulePublisher, stateStore, performanceMetrics, null);
    }

    /** Unit-test/source compatibility constructor with a caller-provided state store. */
    public DetectEngineService(RuleSpecStore store, RecentAlertSink sink, AlertForwarder forwarder,
                               RuleChangePublisher rulePublisher, DetectionStateStore stateStore) {
        this.store = store;
        this.sink = sink;
        this.forwarder = forwarder;
        this.ruleService = new DetectionRuleService(store, rulePublisher);
        this.stateStore = stateStore;
        this.snapshotStore = null;
        this.stateOwnership = DetectionStateOwnership.noop();
        this.engineFactory = new DetectionEngineFactory(store, sink, suppressor, RuleProcessingObserver.NOOP);
    }

    /** Unit-test/source compatibility constructor; production uses the JPA journal. */
    public DetectEngineService(RuleSpecStore store, RecentAlertSink sink, AlertForwarder forwarder,
                               RuleChangePublisher rulePublisher) {
        this(store, sink, forwarder, rulePublisher, new InMemoryDetectionStateStore());
    }

    public RecoveryStatus recoveryStatus() {
        RecoveryStatus worst = recoveryStatus.get();
        for (RecoveryStatus status : engineRecovery.values()) {
            if (status.ordinal() > worst.ordinal()) worst = status;
        }
        return worst;
    }

    public boolean isReady() {
        return recoveryStatus() == RecoveryStatus.READY;
    }

    public String recoveryFailure() {
        return recoveryFailure;
    }

    /**
     * Admission decision for one event. Only the recovery scope of the engine
     * that would evaluate it - and the process-wide scope, which really does
     * replace every engine - can hold this work back.
     */
    private boolean readyFor(String tenant, int shard) {
        if (recoveryStatus.get() != RecoveryStatus.READY) return false;
        RecoveryStatus status = engineRecovery.get(engineKey(resolveTenant(tenant), shard));
        return status == null || status == RecoveryStatus.READY;
    }

    /** The recovery status of exactly one engine scope. */
    private RecoveryStatus recoveryStatusFor(String tenant, int shard) {
        if (recoveryStatus.get() != RecoveryStatus.READY) return recoveryStatus.get();
        RecoveryStatus status = engineRecovery.get(engineKey(resolveTenant(tenant), shard));
        return status == null ? RecoveryStatus.READY : status;
    }

    /**
     * Hold one engine scope shut until the recovery schedule rebuilds it. Other
     * tenants and shards keep accepting work: this is no longer a process gate.
     */
    private void markKeyDegraded(String tenant, int shard, Throwable failure) {
        String key = engineKey(resolveTenant(tenant), shard);
        engineRecovery.put(key, RecoveryStatus.DEGRADED);
        pendingRebuilds.put(key, describe(failure));
        recoveryFailure = describe(failure);
        org.slf4j.LoggerFactory.getLogger(DetectEngineService.class)
                .warn("Detection engine degraded key={}: {}", key, describe(failure), failure);
    }

    private void markRecovering() {
        recoveryFailure = null;
        recoveryStatus.set(RecoveryStatus.RECOVERING);
    }

    private void markReady() {
        recoveryFailure = null;
        recoveryStatus.set(RecoveryStatus.READY);
        engineRecovery.clear();
    }

    private void markDegraded(Throwable failure) {
        recoveryFailure = describe(failure);
        recoveryStatus.set(RecoveryStatus.DEGRADED);
        org.slf4j.LoggerFactory.getLogger(DetectEngineService.class)
                .error("Detection state recovery failed; readiness is degraded", failure);
    }

    private void markKeysRecovering(java.util.Collection<String> keys) {
        for (String key : keys) engineRecovery.put(key, RecoveryStatus.RECOVERING);
    }

    private void markKeysReady(java.util.Collection<String> keys) {
        for (String key : keys) {
            engineRecovery.remove(key);
            pendingRebuilds.remove(key);
        }
    }

    /**
     * A tenant-scoped rebuild failure is reported and retried, but it does not
     * stop ingestion while the engines that served this tenant before the
     * attempt are still alive. Only a key with no live engine is held shut.
     */
    private void markKeysAfterFailedRebuild(java.util.Collection<String> keys, Throwable failure) {
        recoveryFailure = describe(failure);
        for (String key : keys) {
            pendingRebuilds.put(key, recoveryFailure);
            if (!engines.containsKey(key)) engineRecovery.put(key, RecoveryStatus.DEGRADED);
            else engineRecovery.remove(key);
        }
        org.slf4j.LoggerFactory.getLogger(DetectEngineService.class).warn(
                "Detection engine rebuild failed; previous engines stay in service and the rebuild"
                        + " is retried on the recovery schedule: {}", describe(failure), failure);
    }

    private static String describe(Throwable failure) {
        if (failure == null) return "unknown recovery failure";
        String message = failure.getMessage();
        return message == null || message.isBlank()
                ? failure.getClass().getSimpleName() : failure.getClass().getSimpleName() + ": " + message;
    }

    @PostConstruct
    public void start() {
        if (!workerRole()) {
            // The API process owns rule/configuration writes only. The worker
            // is the sole process allowed to restore state and create live
            // rule engines, so an API restart cannot touch detection state.
            markReady();
            return;
        }
        markRecovering();
        try {
            com.socp.platform.tenant.context.TenantContext.runWith(
                    "default", () -> engineFor("default"));
            markReady();
        } catch (RuntimeException failure) {
            markDegraded(failure);
        }
    }

    @PreDestroy
    public void stop() {
        engineLifecycle.writeLock().lock();
        try {
            engines.values().forEach(RuleEngine::close);
            engines.clear();
            engineLastAccess.clear();
            snapshotCounters.clear();
            snapshotOffsets.clear();
            engineFactory.clearIsolation();
            engineRecovery.clear();
            pendingRebuilds.clear();
            suppressor.close();
            tenantAdmission.clear();
            releaseAllStateLeases();
        } finally {
            engineLifecycle.writeLock().unlock();
        }
    }

    /** Release only the fencing rows owned by this process on partition revoke. */
    public synchronized void releaseForPartitions(Set<Integer> partitions) {
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

    private void releaseAllStateLeases() {
        for (String key : stateLeases.keySet()) {
            Object lock = stateLeaseLocks.get(key);
            if (lock == null) continue;
            synchronized (lock) {
                DetectionStateOwnership.Lease lease = stateLeases.remove(key);
                if (lease != null) stateOwnership.release(lease);
            }
        }
    }

    private RuleEngine buildEngine(String tenant, List<SecurityEvent> history) {
        return buildEngine(engineKey(tenant, 0), tenant, history, null);
    }

    private RuleEngine buildEngine(String engineKey, String tenant, List<SecurityEvent> history,
                                   Runnable durableCommitGuard) {
        return engineFactory.build(engineKey, tenant, history, durableCommitGuard);
    }

    private RuleEngine engineFor(String tenant) {
        return engineFor(tenant, 0);
    }

    private RuleEngine engineFor(String tenant, int shard) {
        String resolved = resolveTenant(tenant);
        int resolvedShard = normalizeShard(shard);
        String key = engineKey(resolved, resolvedShard);
        engineLifecycle.readLock().lock();
        try {
            RuleEngine engine = engines.computeIfAbsent(key, ignored -> {
                try {
                    RuleEngine created = buildEngine(key, resolved, List.of(), null);
                    restoreState(resolved, created, assignedPartitions.get(), resolvedShard);
                    created.start();
                    engineRecovery.remove(key);
                    pendingRebuilds.remove(key);
                    return created;
                } catch (RuntimeException recoveryFailure) {
                    // A lazily created engine that cannot be restored must not
                    // look healthy: only this key is held shut, and the recovery
                    // schedule retries it.
                    markKeyDegraded(resolved, resolvedShard, recoveryFailure);
                    throw recoveryFailure;
                }
            });
            engineLastAccess.put(key, System.currentTimeMillis());
            return engine;
        } finally {
            engineLifecycle.readLock().unlock();
        }
    }

    private int normalizeShard(int shard) {
        return Math.floorMod(shard, effectiveShardCount());
    }

    private static String resolveTenant(String tenant) {
        return tenant == null || tenant.isBlank() ? "default" : tenant;
    }

    private int effectiveShardCount() {
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

    private Runnable durableGuardFor(int partition, int shard) {
        DetectionStateOwnership.Lease lease = stateLeaseFor(partition, shard);
        return lease == null ? () -> { } : () -> stateOwnership.assertCurrent(lease);
    }

    private void acquireStateLeases(Set<Integer> partitions) {
        if (partitions == null) return;
        for (Integer partition : partitions) {
            if (partition == null || partition < 0) continue;
            for (int shard = 0; shard < effectiveShardCount(); shard++) {
                stateLeaseFor(partition, shard);
            }
        }
    }

    private void releaseUnassignedStateLeases(Set<Integer> partitions) {
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

    private static String engineKey(String tenant, int shard) {
        return tenant + "\u0000shard-" + shard;
    }

    private int shardFor(SecurityEvent event) {
        if (effectiveShardCount() == 1) return 0;
        String tenant = event.requireTenantId();
        String field = DetectionRoutingKey.field(event.source(), event.host(), event.fields());
        String value = DetectionRoutingKey.value(event.source(), event.host(), event.fields());
        return new StateRoutingKey(tenant, field, value).shard(effectiveShardCount());
    }

    /**
     * 规则热更新：为该租户的每个 shard 装配新引擎并原子替换。
     *
     * <p>The recovery gate is scoped to this tenant's engine keys, so one
     * tenant's rule edit cannot open a rejection window for the others. A failed
     * rebuild keeps the previously live engines serving and is retried by
     * {@link #retryDegradedEngines()}.</p>
     */
    public synchronized void reload() {
        if (!workerRole()) return;
        String tenant = store.tenant();
        List<String> keys = tenantEngineKeys(tenant);
        markKeysRecovering(keys);
        try {
            replaceTenantEngine(tenant);
            markKeysReady(keys);
        } catch (RuntimeException failure) {
            markKeysAfterFailedRebuild(keys, failure);
        }
    }

    /** Every engine key one tenant owns at the current shard count. */
    private List<String> tenantEngineKeys(String tenant) {
        int shards = effectiveShardCount();
        List<String> keys = new java.util.ArrayList<>(shards);
        for (int shard = 0; shard < shards; shard++) keys.add(engineKey(tenant, shard));
        return List.copyOf(keys);
    }

    /**
     * Rebuild only from the state owned by the current Kafka assignment. The
     * callback is invoked before records from a new assignment are processed,
     * so a rebalance cannot mix windows from another instance's partitions.
     */
    public synchronized void restoreForPartitions(Set<Integer> partitions) {
        com.socp.platform.tenant.context.TenantContext.runAsSystem(
                () -> restoreForPartitions(partitions, false));
    }

    /** Force a state rebuild after a durable sink failure before retrying. */
    public synchronized void rebuildForPartitions(Set<Integer> partitions) {
        com.socp.platform.tenant.context.TenantContext.runAsSystem(
                () -> restoreForPartitions(partitions, true));
    }

    private synchronized void restoreForPartitions(Set<Integer> partitions, boolean force) {
        if (!workerRole()) return;
        if (partitions == null || partitions.isEmpty()) return;
        Set<Integer> normalized = Set.copyOf(partitions);
        if (!force && normalized.equals(assignedPartitions.get())) return;
        markRecovering();
        revokedPartitions.removeAll(normalized);
        assignedPartitions.set(normalized);
        try {
            acquireStateLeases(normalized);
            replaceAllEnginesFromState(normalized);
            // Old engines have drained under the lifecycle write lock before
            // revoked partition leases are released.
            releaseUnassignedStateLeases(normalized);
            markReady();
        } catch (RuntimeException failure) {
            markDegraded(failure);
        }
    }

    /** Used when Kafka is disabled or for an operational full-state replay. */
    public synchronized void restoreAll() {
        if (!workerRole()) return;
        com.socp.platform.tenant.context.TenantContext.runAsSystem(() -> {
            markRecovering();
            revokedPartitions.clear();
            assignedPartitions.set(Set.of());
            try {
                replaceAllEnginesFromState(Set.of());
                releaseUnassignedStateLeases(Set.of());
                markReady();
            } catch (RuntimeException failure) {
                markDegraded(failure);
            }
        });
    }

    /**
     * Rebuild engines that a scoped recovery left behind. Detection keeps
     * serving from whatever engine was already live, so a failed rebuild used to
     * stay failed until an operator edited a rule again; this closes that gap.
     */
    @Scheduled(fixedDelayString = "${socp.detect.recovery.retry-interval-ms:30000}",
            initialDelayString = "${socp.detect.recovery.retry-initial-delay-ms:30000}")
    void retryDegradedEngines() {
        if (!workerRole()) return;
        if (pendingRebuilds.isEmpty() && engineRecovery.isEmpty()
                && recoveryStatus.get() == RecoveryStatus.READY) return;
        rebuildRetries.incrementAndGet();
        Set<String> keys = new java.util.LinkedHashSet<>(pendingRebuilds.keySet());
        for (Map.Entry<String, RecoveryStatus> entry : engineRecovery.entrySet()) {
            if (entry.getValue() != RecoveryStatus.READY) keys.add(entry.getKey());
        }
        com.socp.platform.tenant.context.TenantContext.runAsSystem(() -> {
            if (recoveryStatus.get() == RecoveryStatus.DEGRADED) {
                // The process scope failed at startup or on a full assignment
                // rebuild; that rebuild replaces every engine, so retry it as a
                // whole rather than one key at a time.
                try {
                    replaceAllEnginesFromState(assignedPartitions.get());
                    releaseUnassignedStateLeases(assignedPartitions.get());
                    markReady();
                } catch (RuntimeException failure) {
                    markDegraded(failure);
                }
                return;
            }
            for (String key : keys) {
                String tenant = tenantOfEngineKey(key);
                if (tenant == null) continue;
                try {
                    replaceTenantEngine(tenant);
                    engineRecovery.remove(key);
                    pendingRebuilds.remove(key);
                    org.slf4j.LoggerFactory.getLogger(DetectEngineService.class)
                            .info("Detection engine rebuild recovered key={}", key);
                } catch (RuntimeException failure) {
                    markKeysAfterFailedRebuild(List.of(key), failure);
                }
            }
        });
    }

    /** Reverse of {@link #engineKey}: the tenant part of an engine key. */
    private static String tenantOfEngineKey(String key) {
        if (key == null) return null;
        int marker = key.lastIndexOf("shard-");
        return marker <= 1 ? null : key.substring(0, marker - 1);
    }

    private void restoreState(String tenant, RuleEngine replacement, Set<Integer> partitions, int shard) {
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
                    org.slf4j.LoggerFactory.getLogger(DetectEngineService.class).info(
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

    public Set<Integer> assignedPartitions() {
        return assignedPartitions.get();
    }

    /**
     * Fence the final journal transition with the same state-unit ownership used
     * by durable rule side effects. A revoked worker may observe an async task
     * complete, but it must not mark that event completed after the epoch moved.
     */
    public void assertCurrentOwner(SecurityEvent event, Integer partition) {
        if (event == null || partition == null || partition < 0) return;
        engineLifecycle.readLock().lock();
        try {
            if (revokedPartitions.contains(partition)
                    || !assignedPartitions.get().contains(partition)) {
                throw new DetectionStateOwnership.StaleStateOwnerException(
                        "Kafka partition is no longer assigned: " + partition);
            }
            durableGuardFor(partition, shardFor(event)).run();
        } finally {
            engineLifecycle.readLock().unlock();
        }
    }

    /**
     * Build this tenant's replacement engines, restore them from durable state,
     * and only then swap them in.
     *
     * <p>Compilation happens outside the lifecycle lock so a slow rule store does
     * not stall ingestion. Restoration happens under the write lock - after all
     * accepted work has drained - but before the live engines are closed, so a
     * failed rebuild leaves this tenant detecting on the engines it was already
     * using instead of taking it down.</p>
     */
    private void replaceTenantEngine(String tenant) {
        String resolvedTenant = resolveTenant(tenant);
        List<String> keys = tenantEngineKeys(resolvedTenant);
        Map<String, RuleEngine> replacements = new LinkedHashMap<>();
        try {
            for (int shard = 0; shard < keys.size(); shard++) {
                replacements.put(keys.get(shard),
                        buildEngine(keys.get(shard), resolvedTenant, List.of(), null));
            }
            engineLifecycle.writeLock().lock();
            try {
                // The replacement reads the Journal only after admission is
                // closed and accepted work has drained, so its hot state
                // includes every durable completion before the swap. Draining
                // without closing is what keeps the live engine servable when the
                // rebuild afterwards fails.
                for (String key : keys) {
                    RuleEngine live = engines.get(key);
                    if (live == null || live.awaitIdle(reloadDrainTimeoutMs)) continue;
                    throw new IllegalStateException("detection engine did not drain before"
                            + " hot reload key=" + key + " budgetMs=" + reloadDrainTimeoutMs);
                }
                for (int shard = 0; shard < keys.size(); shard++) {
                    restoreState(resolvedTenant, replacements.get(keys.get(shard)),
                            assignedPartitions.get(), shard);
                }
                for (String key : keys) {
                    RuleEngine old = engines.remove(key);
                    engineLastAccess.remove(key);
                    if (old != null) old.close();
                }
                replacements.forEach((key, replacement) -> {
                    replacement.start();
                    engines.put(key, replacement);
                    engineLastAccess.put(key, System.currentTimeMillis());
                });
            } finally {
                engineLifecycle.writeLock().unlock();
            }
        } catch (RuntimeException failure) {
            // Nothing was published, so only the candidates are released. The
            // engines that were live keep serving this tenant.
            replacements.values().forEach(RuleEngine::close);
            throw failure;
        }
    }

    private void replaceAllEnginesFromState(Set<Integer> partitions) {
        engineLifecycle.writeLock().lock();
        try {
            engines.values().forEach(RuleEngine::close);
            engines.clear();
            engineLastAccess.clear();
            snapshotCounters.clear();
            snapshotOffsets.clear();
            engineFactory.clearIsolation();
            java.util.function.Consumer<List<SecurityEvent>> restoreBatch = events -> {
                Map<String, List<SecurityEvent>> byEngine = events.stream()
                        .collect(java.util.stream.Collectors.groupingBy(event ->
                                engineKey(event.tenantId(), shardFor(event))));
                byEngine.forEach((key, owned) -> {
                    String tenant = tenantOfEngineKey(key);
                    RuleEngine engine = engines.get(key);
                    if (engine == null) {
                        engine = buildEngine(key, tenant, List.of(), null);
                        engines.put(key, engine);
                    }
                    engine.restore(owned);
                    engineLastAccess.put(key, System.currentTimeMillis());
                });
            };
            if (partitions == null || partitions.isEmpty()) {
                stateStore.replayRecent(configuredRecoveryWindow(), restoreBatch);
            } else {
                stateStore.replayRecentForPartitions(partitions, configuredRecoveryWindow(), restoreBatch);
            }
            // Keep an empty default shard warm when no history exists. Do not
            // call engineFor() here: that path restores the journal again and
            // would double the replay cost after the full replay above.
            // Other tenant/shard engines are started only when their first
            // event is admitted, avoiding an O(tenants × shards) startup storm.
            if (engines.isEmpty()) {
                // The full-window replay above is authoritative: this engine has
                // no journaled state at all. Say so, instead of letting a warm
                // empty engine look like a checkpoint-restored one.
                warmedWithoutHistory.incrementAndGet();
                org.slf4j.LoggerFactory.getLogger(DetectEngineService.class).info(
                        "Detection state rebuild found no journaled history; warming an empty"
                                + " default engine partitions={}", partitions);
                String key = engineKey("default", 0);
                engines.put(key, buildEngine(key, "default", List.of(), null));
                engineLastAccess.put(key, System.currentTimeMillis());
            }
            engines.values().forEach(RuleEngine::start);
        } catch (RuntimeException failure) {
            engines.values().forEach(RuleEngine::close);
            engines.clear();
            engineLastAccess.clear();
            snapshotCounters.clear();
            snapshotOffsets.clear();
            engineFactory.clearIsolation();
            throw failure;
        } finally {
            engineLifecycle.writeLock().unlock();
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

    @Scheduled(fixedDelayString = "${socp.detect.engine.cleanup-interval-ms:60000}")
    void evictIdleEngines() {
        engineLifecycle.writeLock().lock();
        try {
            long now = System.currentTimeMillis();
            long safeTtl = Math.max(60_000L, engineIdleTtlMs);
            List<String> candidates = new java.util.ArrayList<>();
            for (String tenant : engines.keySet()) {
                if (now - engineLastAccess.getOrDefault(tenant, now) > safeTtl) candidates.add(tenant);
            }
            int remaining = engines.size() - candidates.size();
            int excess = remaining - Math.max(1, maxTenantEngines);
            if (excess > 0) {
                engines.keySet().stream()
                        .filter(tenant -> !candidates.contains(tenant))
                        .sorted(java.util.Comparator.comparingLong(
                                tenant -> engineLastAccess.getOrDefault(tenant, Long.MIN_VALUE)))
                        .limit(excess)
                        .forEach(candidates::add);
            }
            for (String tenant : candidates) {
                RuleEngine removed = engines.remove(tenant);
                engineLastAccess.remove(tenant);
                snapshotCounters.remove(tenant);
                snapshotOffsets.remove(tenant);
                engineFactory.removeIsolation(tenant);
                if (removed == null) {
                    // A pending rebuild for an engine that is no longer cached is
                    // meaningless; the next event rebuilds it lazily instead.
                    engineRecovery.remove(tenant);
                    pendingRebuilds.remove(tenant);
                }
                if (removed != null) removed.close();
            }
        } finally {
            engineLifecycle.writeLock().unlock();
        }
    }

    int cachedTenantEngines() {
        return engines.size();
    }

    public List<Map<String, Object>> listRules() {
        return ruleService.listRules();
    }

    /** Bounded compatibility view for API callers that omit pagination. */
    public List<Map<String, Object>> listRules(int limit) {
        return ruleService.listRules(limit);
    }

    public long ruleCount() {
        return ruleService.ruleCount();
    }

    public org.springframework.data.domain.Page<Map<String, Object>> listRulesPage(int page, int size) {
        return ruleService.listRulesPage(page, size);
    }

    public Map<String, Object> contentManifest() {
        return ruleService.contentManifest();
    }

    @org.springframework.transaction.annotation.Transactional
    public Map<String, Object> addRule(Map<String, Object> spec) {
        Map<String, Object> saved = ruleService.addRule(spec);
        reloadAfterCommit();
        return saved;
    }

    @org.springframework.transaction.annotation.Transactional
    public Map<String, Object> updateRule(Map<String, Object> spec) {
        Map<String, Object> saved = ruleService.updateRule(spec);
        reloadAfterCommit();
        return saved;
    }

    /** Promote a tested rule into the live engine under an explicit approval permission. */
    @org.springframework.transaction.annotation.Transactional
    public Map<String, Object> activateRule(String id) {
        Map<String, Object> activated = ruleService.activateRule(id);
        reloadAfterCommit();
        return activated;
    }

    @org.springframework.transaction.annotation.Transactional
    public boolean deleteRule(String id) {
        boolean removed = ruleService.deleteRule(id);
        if (removed) reloadAfterCommit();
        return removed;
    }

    private void reloadAfterCommit() {
        if (!org.springframework.transaction.support.TransactionSynchronizationManager
                .isSynchronizationActive()) {
            reload();
            return;
        }
        org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                new org.springframework.transaction.support.TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        reload();
                    }
                });
    }

    /** 事件摄取：队列满回 false（接入端据此回 503 + Retry-After） */
    public boolean ingest(SecurityEvent ev) {
        engineLifecycle.readLock().lock();
        try {
            int shard = shardFor(ev);
            if (!workerRole() || !readyFor(ev.tenantId(), shard)) return false;
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
                RuleEngine target = engineFor(ev.tenantId(), shard);
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
                    stateStore.markCompleted(ev);
                    snapshotAfterDurable(ev, null, -1L);
                }
            });
            return true;
        } finally {
            engineLifecycle.readLock().unlock();
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
        return ingestFromKafkaAndAwait(ev, inputTopic, partition, offset);
    }

    /** Kafka ingestion carrying the source topic into the durable result envelope. */
    public CompletableFuture<Void> ingestFromKafkaAndAwait(SecurityEvent ev, String topic,
                                                           Integer partition, Long offset) {
        engineLifecycle.readLock().lock();
        try {
            if (!workerRole()) {
                return CompletableFuture.failedFuture(
                        new RuntimeUnavailableException("detection runtime role is " + runtimeRole));
            }
            int shard = shardFor(ev);
            if (!readyFor(ev.requireTenantId(), shard)) {
                return CompletableFuture.failedFuture(new RuntimeUnavailableException(
                        "detection state recovery is "
                                + recoveryStatusFor(ev.requireTenantId(), shard).name()));
            }
            TenantAdmission.Decision admission = admit(ev);
            if (!admission.admitted()) {
                return CompletableFuture.failedFuture(new TenantAdmission.RejectedException(
                        ev.requireTenantId(), admission.reason()));
            }
            TenantAdmission.Permit permit = admission.permit();
            try {
                RuleEngine target = engineFor(ev.tenantId(), shard);
                int statePartition = partition == null ? -1 : partition;
                Runnable ownershipGuard = durableGuardFor(statePartition, shard);
                Runnable durablePosition = () -> recordDurablePosition(ev, partition, offset);
                com.socp.rule.engine.DetectionResult.InputPosition inputPosition =
                        partition == null || offset == null
                                ? com.socp.rule.engine.DetectionResult.InputPosition.unknown()
                                : new com.socp.rule.engine.DetectionResult.InputPosition(
                                topic, partition, offset);
                CompletableFuture<Void> durable = target.ingestAndAwait(
                        ev, inputPosition, durablePosition, ownershipGuard);
                // Keep the snapshot tied to the engine instance that processed
                // the event. A concurrent hot reload can replace the map entry
                // before the caller observes completion; looking the engine up
                // again there could checkpoint the fresh, empty replacement.
                return durable.thenRun(() -> snapshotAfterDurable(target, ev, partition, offset))
                        .whenComplete((ignored, failure) -> tenantAdmission.release(permit));
            } catch (RuntimeException recoveryFailure) {
                tenantAdmission.rollback(permit);
                return CompletableFuture.failedFuture(recoveryFailure);
            }
        } finally {
            engineLifecycle.readLock().unlock();
        }
    }

    private void recordDurablePosition(SecurityEvent event, Integer partition, Long offset) {
        if (event == null || partition == null || offset == null || partition < 0 || offset < 0) return;
        String key = engineKey(event.requireTenantId(), shardFor(event));
        snapshotOffsets.computeIfAbsent(key, ignored -> new ConcurrentHashMap<>())
                .merge(partition, offset, Math::max);
    }

    /** Persist a versioned rule-state checkpoint after durable sink completion. */
    public void snapshotAfterDurable(SecurityEvent event, Integer partition, Long offset) {
        if (snapshotStore == null || event == null) return;
        snapshotAfterDurable(engineFor(event.tenantId(), shardFor(event)), event, partition, offset);
    }

    /** Capture from a known engine instance; used by its worker callback during a hot swap. */
    private void snapshotAfterDurable(RuleEngine engine, SecurityEvent event,
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
            org.slf4j.LoggerFactory.getLogger(DetectEngineService.class).warn(
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

    private boolean enqueue(SecurityEvent ev) {
        engineLifecycle.readLock().lock();
        try {
            int shard = shardFor(ev);
            if (!readyFor(ev.tenantId(), shard)) return false;
            TenantAdmission.Decision admission = admit(ev);
            if (!admission.admitted()) return false;
            TenantAdmission.Permit permit = admission.permit();
            try {
                RuleEngine.Submission submission = engineFor(ev.tenantId(), shard).submit(ev, false);
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
            engineLifecycle.readLock().unlock();
        }
    }

    public List<Alert> recentAlerts() {
        return sink.recent(store.tenant());
    }

    public Map<String, Object> stats() {
        String tenant = store.tenant();
        String keyPrefix = tenant + "\u0000shard-";
        List<RuleEngine> tenantEngines = engines.entrySet().stream()
                .filter(entry -> entry.getKey().startsWith(keyPrefix))
                .map(Map.Entry::getValue)
                .toList();
        if (tenantEngines.isEmpty() && workerRole() && readyFor(tenant, 0)) {
            tenantEngines = List.of(engineFor(tenant, 0));
        }
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
        m.put("isolatedRules", engineFactory.isolatedCount(keyPrefix));
        m.put("routingMismatchWindows", tenantEngines.stream()
                .mapToLong(RuleEngine::routingMismatchWindows).sum());
        m.put("instance", instanceId);
        m.put("assignedPartitions", assignedPartitions.get());
        m.put("pendingEvents", stateStore.pendingCount(tenant));
        Map<String, Object> recovery = new LinkedHashMap<>();
        recovery.put("status", recoveryStatus().name());
        recovery.put("ready", isReady());
        if (recoveryFailure != null) recovery.put("error", recoveryFailure);
        // Recovery is scoped: name the engines that are not ready instead of
        // letting one key look like a process-wide outage. Only this tenant's
        // keys are shown; the retry counters below describe this replica.
        Map<String, RecoveryStatus> scopes = new LinkedHashMap<>();
        engineRecovery.forEach((key, status) -> {
            if (key.startsWith(keyPrefix)) scopes.put(key, status);
        });
        Map<String, String> pending = new LinkedHashMap<>();
        pendingRebuilds.forEach((key, reason) -> {
            if (key.startsWith(keyPrefix)) pending.put(key, reason);
        });
        if (!scopes.isEmpty()) recovery.put("engineScopes", Map.copyOf(scopes));
        if (!pending.isEmpty()) recovery.put("pendingRebuilds", Map.copyOf(pending));
        recovery.put("scope", "replica-local");
        recovery.put("rebuildRetries", rebuildRetries.get());
        recovery.put("warmedWithoutHistory", warmedWithoutHistory.get());
        recovery.put("store", stateStore.getClass().getSimpleName());
        String recoveryWindow = stateStore.recoveryWindow();
        recovery.put("replayWindow", recoveryWindow == null ? "unknown" : recoveryWindow);
        m.put("stateRecovery", recovery);
        m.put("stateSnapshots", Map.of(
                "store", snapshotStore == null ? "disabled" : snapshotStore.getClass().getSimpleName(),
                "everyEvents", Math.max(1L, snapshotEveryEvents),
                "shards", effectiveShardCount()));
        m.put("runtimeRole", runtimeRole);
        m.put("detectionWorkerEnabled", workerRole());
        m.put("cachedTenantEngines", engines.size());
        m.put("tenantAdmission", tenantAdmission.stats(tenant));
        m.put("tenantAdmissionRejections", tenantAdmission.rejectionStats(tenant));
        return m;
    }

    private TenantAdmission.Decision admit(SecurityEvent event) {
        tenantAdmission.configure(tenantMaxEventsPerSecond, tenantRateBurst,
                tenantMaxPendingBytes, tenantMaxActiveEntities, tenantEntityIdleTtlMs);
        return tenantAdmission.tryAcquire(event, TenantAdmission.estimateBytes(event));
    }

    private boolean workerRole() {
        String role = runtimeRole == null ? "all" : runtimeRole.trim();
        return "all".equalsIgnoreCase(role) || "worker".equalsIgnoreCase(role);
    }
}
