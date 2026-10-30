package com.socp.detect.web.service;

import com.socp.detect.web.engine.RecentAlertSink;
import com.socp.detect.web.metrics.DetectionPerformanceMetrics;
import com.socp.detect.web.persistence.store.RuleSpecStore;
import com.socp.detect.web.service.DetectEngineService.RecoveryStatus;
import com.socp.rule.engine.RuleEngine;
import com.socp.rule.engine.RuleProcessingObserver;
import com.socp.rule.engine.Suppressor;
import com.socp.rule.model.SecurityEvent;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import static com.socp.detect.web.service.DetectionStateRecovery.engineKey;
import static com.socp.detect.web.service.DetectionStateRecovery.resolveTenant;
import static com.socp.detect.web.service.DetectionStateRecovery.tenantOfEngineKey;

/**
 * Owns live engines and their lifecycle. All admission paths share its read lock;
 * draining, replacement and eviction use the same fair write lock. Rebalance and
 * rule-reload transitions remain serialized on this component's monitor.
 */
@Component
public class DetectionEngineLifecycle {
    private final RuleSpecStore store;
    private final DetectionStateRecovery state;
    private final Suppressor suppressor = new Suppressor(Duration.ofMinutes(5));
    private final DetectionEngineFactory engineFactory;
    private final Map<String, RuleEngine> engines = new ConcurrentHashMap<>();
    /** Acquired only after the lifecycle lock; cold restores never run concurrently. */
    private final java.util.concurrent.locks.ReentrantLock coldRestore = new java.util.concurrent.locks.ReentrantLock();
    private volatile boolean lazyAssignmentRecovery;
    private final Map<String, Long> engineLastAccess = new ConcurrentHashMap<>();
    private final AtomicLong warmedWithoutHistory = new AtomicLong();
    // Process-wide rebuilds and tenant/shard rebuilds intentionally have different gates.
    private final AtomicReference<RecoveryStatus> recoveryStatus = new AtomicReference<>(RecoveryStatus.READY);
    private final Map<String, RecoveryStatus> engineRecovery = new ConcurrentHashMap<>();
    private final Map<String, String> pendingRebuilds = new ConcurrentHashMap<>();
    private final AtomicLong rebuildRetries = new AtomicLong();
    private volatile String recoveryFailure;
    private final ReentrantReadWriteLock engineLifecycle = new ReentrantReadWriteLock(true);

    @Value("${socp.detect.runtime-role:all}")
    private String runtimeRole = "all";

    @Value("${socp.detect.engine.idle-ttl-ms:1800000}")
    private long engineIdleTtlMs = 30 * 60 * 1000L;

    @Value("${socp.detect.engine.max-tenants:1000}")
    private int maxTenantEngines = 1000;

    /**
     * How long a hot reload waits for the live engine to finish accepted work
     * before it reads the Journal. A rebuild that cannot drain in this budget is
     * abandoned with the live engines still serving, instead of swapping in state
     * that is missing an in-flight completion.
     */
    @Value("${socp.detect.reload.drain-timeout-ms:30000}")
    private long reloadDrainTimeoutMs = 30_000L;

    @Autowired
    public DetectionEngineLifecycle(RuleSpecStore store, RecentAlertSink sink,
                                    DetectionPerformanceMetrics metrics, DetectionStateRecovery state) {
        this(store, sink, (RuleProcessingObserver) metrics, state);
    }

    DetectionEngineLifecycle(RuleSpecStore store, RecentAlertSink sink,
                             RuleProcessingObserver observer, DetectionStateRecovery state) {
        this.store = store;
        this.state = state;
        this.engineFactory = new DetectionEngineFactory(store, sink, suppressor, observer, state::effectiveShardCount);
    }

    Lock readLock() { return engineLifecycle.readLock(); }

    String runtimeRole() { return runtimeRole; }

    RecoveryStatus recoveryStatus() {
        RecoveryStatus worst = recoveryStatus.get();
        for (RecoveryStatus status : engineRecovery.values()) {
            if (status.ordinal() > worst.ordinal()) worst = status;
        }
        return worst;
    }

    boolean isReady() {
        return recoveryStatus() == RecoveryStatus.READY;
    }

    String recoveryFailure() {
        return recoveryFailure;
    }

    /**
     * Admission decision for one event. Only the recovery scope of the engine
     * that would evaluate it - and the process-wide scope, which really does
     * replace every engine - can hold this work back.
     */
    boolean readyFor(String tenant, int shard) {
        if (recoveryStatus.get() != RecoveryStatus.READY) return false;
        RecoveryStatus status = engineRecovery.get(engineKey(resolveTenant(tenant), shard));
        return status == null || status == RecoveryStatus.READY;
    }

    /** The recovery status of exactly one engine scope. */
    RecoveryStatus recoveryStatusFor(String tenant, int shard) {
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
        org.slf4j.LoggerFactory.getLogger(DetectionEngineLifecycle.class)
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
        org.slf4j.LoggerFactory.getLogger(DetectionEngineLifecycle.class)
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
        org.slf4j.LoggerFactory.getLogger(DetectionEngineLifecycle.class).warn(
                "Detection engine rebuild failed; previous engines stay in service and the rebuild"
                        + " is retried on the recovery schedule: {}", describe(failure), failure);
    }

    private static String describe(Throwable failure) {
        if (failure == null) return "unknown recovery failure";
        String message = failure.getMessage();
        return message == null || message.isBlank()
                ? failure.getClass().getSimpleName() : failure.getClass().getSimpleName() + ": " + message;
    }

    void start() {
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

    void stop() {
        engineLifecycle.writeLock().lock();
        try {
            engines.values().forEach(RuleEngine::close);
            engines.clear();
            engineLastAccess.clear();
            state.clearCheckpoints();
            engineFactory.clearIsolation();
            engineRecovery.clear();
            pendingRebuilds.clear();
            suppressor.close();
            state.releaseAllStateLeases();
        } finally {
            engineLifecycle.writeLock().unlock();
        }
    }

    synchronized void releaseForPartitions(Set<Integer> partitions) {
        state.releaseForPartitions(partitions);
    }

    private RuleEngine engineFor(String tenant) {
        return engineFor(tenant, 0);
    }

    RuleEngine engineFor(String tenant, int shard) {
        String resolved = resolveTenant(tenant);
        int resolvedShard = state.normalizeShard(shard);
        String key = engineKey(resolved, resolvedShard);
        var assignment = state.recoveryAssignment();
        engineLifecycle.readLock().lock();
        try {
            RuleEngine engine = engines.get(key);
            if (engine == null) {
                try {
                    coldRestore.lockInterruptibly();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new DetectEngineService.RuntimeUnavailableException("cold state restoration interrupted");
                }
                try {
                    engine = engines.get(key);
                    if (engine == null) {
                        if (!state.isCurrentAssignment(assignment) || Thread.currentThread().isInterrupted()) {
                            throw new com.socp.detect.web.persistence.store.DetectionStateOwnership.StaleStateOwnerException(
                                    "assignment changed while waiting for state restoration");
                        }
                        RuleEngine created = null;
                        try {
                            created = engineFactory.build(key, resolved, List.of(), null);
                            state.restoreState(resolved, created, assignment.partitions(), resolvedShard);
                            RuleEngine restored = created;
                            state.publishForAssignment(assignment, () -> {
                                restored.start();
                                engines.put(key, restored);
                                engineRecovery.remove(key);
                                pendingRebuilds.remove(key);
                            });
                            engine = created;
                        } catch (RuntimeException failure) {
                            if (created != null) created.close();
                            if (state.isCurrentAssignment(assignment) && !Thread.currentThread().isInterrupted()) {
                                markKeyDegraded(resolved, resolvedShard, failure);
                            }
                            throw failure;
                        }
                    }
                } finally {
                    coldRestore.unlock();
                }
            }
            engineLastAccess.put(key, System.currentTimeMillis());
            return engine;
        } finally {
            engineLifecycle.readLock().unlock();
        }
    }

    /**
     * 规则热更新：为该租户的每个 shard 装配新引擎并原子替换。
     *
     * <p>The recovery gate is scoped to this tenant's engine keys, so one
     * tenant's rule edit cannot open a rejection window for the others. A failed
     * rebuild keeps the previously live engines serving and is retried by
     * {@link #retryDegradedEngines()}.</p>
     */
    synchronized void reload() {
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
        int shards = state.effectiveShardCount();
        List<String> keys = new java.util.ArrayList<>(shards);
        for (int shard = 0; shard < shards; shard++) keys.add(engineKey(tenant, shard));
        return List.copyOf(keys);
    }

    /**
     * Rebuild only from the state owned by the current Kafka assignment. The
     * callback is invoked before records from a new assignment are processed,
     * so a rebalance cannot mix windows from another instance's partitions.
     */
    synchronized void restoreForPartitions(Set<Integer> partitions) {
        com.socp.platform.tenant.context.TenantContext.runAsSystem(
                () -> restoreForPartitions(partitions, false));
    }

    /** Force a state rebuild after a durable sink failure before retrying. */
    synchronized void rebuildForPartitions(Set<Integer> partitions) {
        com.socp.platform.tenant.context.TenantContext.runAsSystem(
                () -> restoreForPartitions(partitions, true));
    }

    private synchronized void restoreForPartitions(Set<Integer> partitions, boolean force) {
        if (!workerRole()) return;
        if (partitions == null || partitions.isEmpty()) return;
        Set<Integer> normalized = Set.copyOf(partitions);
        if (!force && normalized.equals(state.assignedPartitions())) return;
        markRecovering();
        state.assign(normalized);
        try {
            state.acquireStateLeases(normalized);
            replaceAllEnginesFromState(normalized);
            // Old engines have drained under the lifecycle write lock before
            // revoked partition leases are released.
            state.releaseUnassignedStateLeases(normalized);
            markReady();
        } catch (RuntimeException failure) {
            markDegraded(failure);
        }
    }

    /** Used when Kafka is disabled or for an operational full-state replay. */
    synchronized void restoreAll() {
        if (!workerRole()) return;
        com.socp.platform.tenant.context.TenantContext.runAsSystem(() -> {
            markRecovering();
            state.clearAssignment();
            try {
                replaceAllEnginesFromState(Set.of());
                state.releaseUnassignedStateLeases(Set.of());
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
    synchronized void retryDegradedEngines() {
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
                    state.acquireStateLeases(state.assignedPartitions());
                    replaceAllEnginesFromState(state.assignedPartitions());
                    state.releaseUnassignedStateLeases(state.assignedPartitions());
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
                    org.slf4j.LoggerFactory.getLogger(DetectionEngineLifecycle.class)
                            .info("Detection engine rebuild recovered key={}", key);
                } catch (RuntimeException failure) {
                    markKeysAfterFailedRebuild(List.of(key), failure);
                }
            }
        });
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
                        engineFactory.build(keys.get(shard), resolvedTenant, List.of(), null));
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
                    state.restoreState(resolvedTenant, replacements.get(keys.get(shard)),
                            state.assignedPartitions(), shard);
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
            lazyAssignmentRecovery = false;
            state.clearCheckpoints();
            engineFactory.clearIsolation();
            java.util.function.Consumer<List<SecurityEvent>> restoreBatch = events -> {
                Map<String, List<SecurityEvent>> byEngine = events.stream()
                        .collect(java.util.stream.Collectors.groupingBy(event ->
                                engineKey(event.tenantId(), state.shardFor(event))));
                byEngine.forEach((key, owned) -> {
                    String tenant = tenantOfEngineKey(key);
                    RuleEngine engine = engines.get(key);
                    if (engine == null) {
                        engine = engineFactory.build(key, tenant, List.of(), null);
                        engines.put(key, engine);
                    }
                    engine.restore(owned);
                    engineLastAccess.put(key, System.currentTimeMillis());
                });
            };
            boolean[] hasHistory = {false};
            boolean deferHistorical = partitions != null && !partitions.isEmpty();
            boolean restoredByTenant = state.restoreTenants(partitions, tenant -> {
                hasHistory[0] = true;
                if (deferHistorical) return;
                for (int shard = 0; shard < state.effectiveShardCount(); shard++) {
                    String key = engineKey(tenant, shard);
                    RuleEngine replacement = engineFactory.build(key, tenant, List.of(), null);
                    engines.put(key, replacement);
                    state.restoreState(tenant, replacement, partitions, shard);
                    engineLastAccess.put(key, System.currentTimeMillis());
                }
            });
            lazyAssignmentRecovery = deferHistorical && restoredByTenant;
            if (!restoredByTenant) state.replayRecent(partitions, restoreBatch);
            // Discovery is payload-free, but an older default checkpoint may
            // survive beyond the journal window. Inspect it before calling this
            // a history-free warmup. Historical tenants otherwise stay cold.
            if (engines.isEmpty() && (!lazyAssignmentRecovery || !hasHistory[0])) {
                String key = engineKey("default", 0);
                RuleEngine empty = engineFactory.build(key, "default", List.of(), null);
                boolean restoredDefault = lazyAssignmentRecovery
                        && state.restoreState("default", empty, partitions, 0);
                if (!restoredDefault) {
                    warmedWithoutHistory.incrementAndGet();
                    org.slf4j.LoggerFactory.getLogger(DetectionEngineLifecycle.class).info(
                            "Detection state rebuild found no journaled history; warming an empty"
                                    + " default engine partitions={}", partitions);
                }
                engines.put(key, empty);
                engineLastAccess.put(key, System.currentTimeMillis());
            }
            engines.values().forEach(RuleEngine::start);
        } catch (RuntimeException failure) {
            engines.values().forEach(RuleEngine::close);
            engines.clear();
            engineLastAccess.clear();
            lazyAssignmentRecovery = false;
            state.clearCheckpoints();
            engineFactory.clearIsolation();
            throw failure;
        } finally {
            engineLifecycle.writeLock().unlock();
        }
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
                state.forgetCheckpoint(tenant);
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

    boolean workerRole() {
        String role = runtimeRole == null ? "all" : runtimeRole.trim();
        return "all".equalsIgnoreCase(role) || "worker".equalsIgnoreCase(role);
    }

    List<RuleEngine> tenantEngines(String tenant) {
        String prefix = tenant + "\u0000shard-";
        // Stats are observational, including before Kafka's first assignment.
        // Only admission or explicit recovery may materialize a cold engine.
        return engines.entrySet().stream()
                .filter(entry -> entry.getKey().startsWith(prefix))
                .map(Map.Entry::getValue).toList();
    }

    int isolatedCount(String tenant) {
        return engineFactory.isolatedCount(tenant + "\u0000shard-");
    }

    Map<String, Object> recoveryStats(String tenant) {
        String keyPrefix = tenant + "\u0000shard-";
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
        long materialized = engines.keySet().stream().filter(key -> key.startsWith(keyPrefix)).count();
        recovery.put("materializedShards", materialized);
        recovery.put("restorationMode", lazyAssignmentRecovery ? "on-first-event" : "eager");
        recovery.put("deferredUntilEvent", workerRole() && materialized == 0 && readyFor(tenant, 0));

        state.describeReplay(recovery);
        return recovery;
    }
}
