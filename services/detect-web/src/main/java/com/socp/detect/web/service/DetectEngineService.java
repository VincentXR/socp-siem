package com.socp.detect.web.service;

import com.socp.detect.web.engine.AlertForwarder;
import com.socp.detect.web.engine.RecentAlertSink;
import com.socp.detect.web.metrics.DetectionPerformanceMetrics;
import com.socp.detect.web.persistence.store.DetectionStateStore;
import com.socp.detect.web.persistence.store.DetectionEventClaim;
import com.socp.detect.web.persistence.store.InMemoryDetectionStateStore;
import com.socp.detect.web.persistence.store.RuleSpecStore;
import com.socp.rule.config.RuleSpec;
import com.socp.rule.engine.RuleEngine;
import com.socp.rule.engine.RuleProcessingObserver;
import com.socp.rule.engine.Suppressor;
import com.socp.rule.model.Alert;
import com.socp.rule.model.SecurityEvent;
import com.socp.rule.partition.DetectionRoutingKey;
import com.socp.rule.rules.Rule;
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

    private final RuleSpecStore store;
    private final RecentAlertSink sink;
    private final AlertForwarder forwarder;
    private final Suppressor suppressor = new Suppressor(Duration.ofMinutes(5));
    private final Map<String, RuleEngine> engines = new ConcurrentHashMap<>();
    private final Map<String, Long> engineLastAccess = new ConcurrentHashMap<>();
    private final RuleChangePublisher rulePublisher;
    private final DetectionStateStore stateStore;
    private final RuleProcessingObserver processingObserver;
    private final DetectionStateSnapshotStore snapshotStore;
    private final Map<String, AtomicLong> snapshotCounters = new ConcurrentHashMap<>();
    private final AtomicReference<Set<Integer>> assignedPartitions = new AtomicReference<>(Set.of());
    private final ReentrantReadWriteLock engineLifecycle = new ReentrantReadWriteLock(true);

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

    @org.springframework.beans.factory.annotation.Autowired
    public DetectEngineService(RuleSpecStore store, RecentAlertSink sink, AlertForwarder forwarder,
                               RuleChangePublisher rulePublisher, DetectionStateStore stateStore,
                               DetectionPerformanceMetrics performanceMetrics,
                               DetectionStateSnapshotStore snapshotStore) {
        this.store = store;
        this.sink = sink;
        this.forwarder = forwarder;
        this.rulePublisher = rulePublisher;
        this.stateStore = stateStore;
        this.processingObserver = performanceMetrics;
        this.snapshotStore = snapshotStore;
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
        this.rulePublisher = rulePublisher;
        this.stateStore = stateStore;
        this.processingObserver = RuleProcessingObserver.NOOP;
        this.snapshotStore = null;
    }

    /** Unit-test/source compatibility constructor; production uses the JPA journal. */
    public DetectEngineService(RuleSpecStore store, RecentAlertSink sink, AlertForwarder forwarder,
                               RuleChangePublisher rulePublisher) {
        this(store, sink, forwarder, rulePublisher, new InMemoryDetectionStateStore());
    }

    @PostConstruct
    public void start() {
        engineFor("default");
    }

    @PreDestroy
    public void stop() {
        engineLifecycle.writeLock().lock();
        try {
            engines.values().forEach(RuleEngine::close);
            engines.clear();
            engineLastAccess.clear();
            snapshotCounters.clear();
            suppressor.close();
        } finally {
            engineLifecycle.writeLock().unlock();
        }
    }

    private RuleEngine buildEngine(String tenant, List<SecurityEvent> history) {
        List<Rule> rules = store.list(tenant).stream()
                .map(RuleSpec::new)
                .filter(spec -> spec.enabled)
                .map(RuleSpec::toRule)
                .toList();
        RuleEngine engine = new RuleEngine(rules, List.of(sink), suppressor, processingObserver);
        try {
            // The journal itself clamps this to its configured retention. 24h
            // covers the bundled UEBA baselines while keeping restart bounded.
            engine.restore(history);
        } catch (Exception ex) {
            // Detection must still start when a stale/corrupt state row exists;
            // the row-level conversion logs the exact event and the next live
            // event continues to be journaled normally.
            org.slf4j.LoggerFactory.getLogger(DetectEngineService.class)
                    .warn("检测状态恢复失败，将以空窗口启动: {}", ex.getMessage());
        }
        return engine;
    }

    private RuleEngine engineFor(String tenant) {
        return engineFor(tenant, 0);
    }

    private RuleEngine engineFor(String tenant, int shard) {
        String resolved = tenant == null || tenant.isBlank() ? "default" : tenant;
        int resolvedShard = normalizeShard(shard);
        String key = engineKey(resolved, resolvedShard);
        engineLifecycle.readLock().lock();
        try {
            RuleEngine engine = engines.computeIfAbsent(key, ignored -> {
                RuleEngine created = buildEngine(resolved, List.of());
                restoreState(resolved, created, assignedPartitions.get(), resolvedShard);
                created.start();
                return created;
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

    private int effectiveShardCount() {
        return Math.max(1, Math.min(256, stateShardCount));
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

    /** 规则热更新：原子替换引擎（旧引擎毒丸退出），无需重启进程 */
    public void reload() {
        replaceTenantEngine(store.tenant());
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
        if (partitions == null || partitions.isEmpty()) return;
        Set<Integer> normalized = Set.copyOf(partitions);
        if (!force && normalized.equals(assignedPartitions.get())) return;
        assignedPartitions.set(normalized);
        replaceAllEnginesFromState(normalized);
    }

    /** Used when Kafka is disabled or for an operational full-state replay. */
    public synchronized void restoreAll() {
        com.socp.platform.tenant.context.TenantContext.runAsSystem(() -> {
            assignedPartitions.set(Set.of());
            replaceAllEnginesFromState(Set.of());
        });
    }

    private void restoreState(String tenant, RuleEngine replacement, Set<Integer> partitions, int shard) {
        try {
            if (snapshotStore != null && stateStore.supportsCheckpointReplay()) {
                Map<String, RuleEngine.RuleState> snapshots = new LinkedHashMap<>();
                java.time.Instant checkpoint = null;
                for (String ruleId : replacement.statefulRuleIds()) {
                    var latest = snapshotStore.latest(tenant, ruleId, normalizeShard(shard));
                    if (latest.isEmpty()) continue;
                    var snapshot = latest.get();
                    snapshots.put(ruleId, new RuleEngine.RuleState(ruleId,
                            snapshot.ruleVersion(), snapshot.serializedState()));
                    if (checkpoint == null || snapshot.snapshotTimestamp().isBefore(checkpoint)) {
                        checkpoint = snapshot.snapshotTimestamp();
                    }
                }
                if (!snapshots.isEmpty() && checkpoint != null) {
                    List<String> restored = replacement.restoreStates(snapshots);
                    if (!restored.isEmpty()) {
                        stateStore.replayCompletedAfter(tenant, checkpoint, partitions,
                                events -> replacement.restore(events.stream()
                                        .filter(event -> tenant.equals(event.tenantId()))
                                        .filter(event -> shardFor(event) == normalizeShard(shard))
                                        .toList()));
                        org.slf4j.LoggerFactory.getLogger(DetectEngineService.class).info(
                                "Detection state restored from snapshots tenant={} rules={} checkpoint={}",
                                tenant, restored.size(), checkpoint);
                        return;
                    }
                }
            }
            if (partitions == null || partitions.isEmpty()) {
                stateStore.replayRecentForTenant(tenant, Duration.ofHours(24), events -> replacement.restore(
                        events.stream().filter(event -> shardFor(event) == normalizeShard(shard)).toList()));
            } else {
                stateStore.replayRecentForPartitions(
                        partitions, Duration.ofHours(24), events -> {
                            List<SecurityEvent> owned = events.stream()
                                    .filter(event -> tenant.equals(event.tenantId()))
                                    .filter(event -> shardFor(event) == normalizeShard(shard))
                                    .toList();
                            if (!owned.isEmpty()) replacement.restore(owned);
                        });
            }
        } catch (Exception ex) {
            org.slf4j.LoggerFactory.getLogger(DetectEngineService.class)
                    .warn("检测状态分页恢复失败，将保留已恢复窗口: {}", ex.getMessage());
        }
    }

    public Set<Integer> assignedPartitions() {
        return assignedPartitions.get();
    }

    private void replaceTenantEngine(String tenant) {
        String resolvedTenant = tenant == null || tenant.isBlank() ? "default" : tenant;
        engineLifecycle.writeLock().lock();
        try {
            // Resolve the rules before closing the live engine so a temporary
            // rule-store outage leaves it available. Once the replacement can
            // be built, stop admission and drain all accepted events before
            // reading the Journal. The new hot state therefore includes every
            // durable completion that happened before the swap.
            Map<String, RuleEngine> replacements = new LinkedHashMap<>();
            for (int shard = 0; shard < effectiveShardCount(); shard++) {
                RuleEngine replacement = buildEngine(resolvedTenant, List.of());
                restoreState(resolvedTenant, replacement, assignedPartitions.get(), shard);
                replacements.put(engineKey(resolvedTenant, shard), replacement);
            }
            List<String> oldKeys = engines.keySet().stream()
                    .filter(key -> key.startsWith(resolvedTenant + "\u0000shard-"))
                    .toList();
            oldKeys.forEach(key -> {
                RuleEngine old = engines.remove(key);
                if (old != null) old.close();
                engineLastAccess.remove(key);
            });
            replacements.forEach((key, replacement) -> {
                replacement.start();
                engines.put(key, replacement);
                engineLastAccess.put(key, System.currentTimeMillis());
            });
        } finally {
            engineLifecycle.writeLock().unlock();
        }
    }

    private void replaceAllEnginesFromState(Set<Integer> partitions) {
        engineLifecycle.writeLock().lock();
        try {
            engines.values().forEach(RuleEngine::close);
            engines.clear();
            engineLastAccess.clear();
            snapshotCounters.clear();
            java.util.function.Consumer<List<SecurityEvent>> restoreBatch = events -> {
                Map<String, List<SecurityEvent>> byEngine = events.stream()
                        .collect(java.util.stream.Collectors.groupingBy(event ->
                                engineKey(event.tenantId(), shardFor(event))));
                byEngine.forEach((key, owned) -> {
                    String tenant = key.substring(0, key.indexOf('\u0000'));
                    RuleEngine engine = engines.get(key);
                    if (engine == null) {
                        engine = buildEngine(tenant, List.of());
                        engines.put(key, engine);
                    }
                    engine.restore(owned);
                    engineLastAccess.put(key, System.currentTimeMillis());
                });
            };
            if (partitions == null || partitions.isEmpty()) {
                stateStore.replayRecent(Duration.ofHours(24), restoreBatch);
            } else {
                stateStore.replayRecentForPartitions(partitions, Duration.ofHours(24), restoreBatch);
            }
            // Keep an empty default shard warm when no history exists. Do not
            // call engineFor() here: that path restores the journal again and
            // would double the replay cost after the full replay above.
            // Other tenant/shard engines are started only when their first
            // event is admitted, avoiding an O(tenants × shards) startup storm.
            if (engines.isEmpty()) {
                String key = engineKey("default", 0);
                engines.put(key, buildEngine("default", List.of()));
                engineLastAccess.put(key, System.currentTimeMillis());
            }
            engines.values().forEach(RuleEngine::start);
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
                snapshotCounters.remove(tenant);
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
        return store.list();
    }

    public Map<String, Object> contentManifest() {
        return store.contentManifest();
    }

    @org.springframework.transaction.annotation.Transactional
    public Map<String, Object> addRule(Map<String, Object> spec) {
        Map<String, Object> saved = store.save(spec);
        rulePublisher.publish(String.valueOf(saved.get("id")), "add");
        reloadAfterCommit();
        return saved;
    }

    @org.springframework.transaction.annotation.Transactional
    public Map<String, Object> updateRule(Map<String, Object> spec) {
        String id = String.valueOf(spec.get("id"));
        Map<String, Object> current = store.get(id);
        if (current == null) {
            throw new IllegalArgumentException("规则不存在: " + spec.get("id"));
        }
        // Preserve lifecycle status when older clients only send the legacy
        // enabled flag. Disabling a live rule is safe; promotion to ACTIVE is
        // intentionally reserved for activateRule().
        spec = new java.util.LinkedHashMap<>(spec);
        if (!spec.containsKey("status") && current.get("status") != null) {
            spec.put("status", current.get("status"));
        }
        if (Boolean.FALSE.equals(spec.get("enabled"))
                && "ACTIVE".equalsIgnoreCase(String.valueOf(current.get("status")))) {
            spec.put("status", "DISABLED");
        }
        Map<String, Object> saved = store.save(spec);
        rulePublisher.publish(String.valueOf(saved.get("id")), "update");
        reloadAfterCommit();
        return saved;
    }

    /** Promote a tested rule into the live engine under an explicit approval permission. */
    @org.springframework.transaction.annotation.Transactional
    public Map<String, Object> activateRule(String id) {
        Map<String, Object> current = store.get(id);
        if (current == null) throw new IllegalArgumentException("rule not found " + id);
        Map<String, Object> activated = new java.util.LinkedHashMap<>(current);
        activated.put("status", "ACTIVE");
        activated.put("enabled", true);
        return updateRule(activated);
    }

    @org.springframework.transaction.annotation.Transactional
    public boolean deleteRule(String id) {
        boolean removed = store.delete(id);
        if (removed) {
            rulePublisher.publish(id, "delete");
            reloadAfterCommit();
        }
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
        DetectionEventClaim claim = stateStore.claim(ev);
        if (claim == DetectionEventClaim.COMPLETED || claim == DetectionEventClaim.DEAD_LETTERED) {
            return true;
        }
        RuleEngine.Submission submission;
        engineLifecycle.readLock().lock();
        try {
            submission = engineFor(ev.tenantId(), shardFor(ev)).submit(ev, true);
        } finally {
            engineLifecycle.readLock().unlock();
        }
        if (!submission.accepted()) {
            if (claim == DetectionEventClaim.NEW) stateStore.remove(ev);
            return false;
        }
        submission.completion().whenComplete((ignored, failure) -> {
            if (failure == null) {
                stateStore.markCompleted(ev);
                snapshotAfterDurable(ev, null, -1L);
            }
        });
        return true;
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
        engineLifecycle.readLock().lock();
        try {
            return engineFor(ev.tenantId(), shardFor(ev)).ingestAndAwait(ev);
        } finally {
            engineLifecycle.readLock().unlock();
        }
    }

    /** Persist a versioned rule-state checkpoint after durable sink completion. */
    public void snapshotAfterDurable(SecurityEvent event, Integer partition, Long offset) {
        if (snapshotStore == null || event == null) return;
        String tenant = event.requireTenantId();
        long every = Math.max(1L, snapshotEveryEvents);
        int shard = shardFor(event);
        String counterKey = engineKey(tenant, shard);
        long count = snapshotCounters.computeIfAbsent(counterKey, ignored -> new AtomicLong()).incrementAndGet();
        if (count % every != 0) return;
        try {
            RuleEngine engine = engineFor(tenant, shard);
            Map<String, RuleEngine.RuleState> states = engine.snapshotStates();
            java.time.Instant timestamp = java.time.Instant.now();
            long processedOffset = offset == null ? -1L : offset;
            states.forEach((ruleId, state) -> snapshotStore.save(new DetectionStateSnapshot(
                    ruleId, state.version(), tenant, shard, processedOffset,
                    state.serializedState(), timestamp)));
        } catch (RuntimeException failure) {
            // Checkpoints are an optimization. The durable journal remains the
            // source of truth when a snapshot write is temporarily unavailable.
            org.slf4j.LoggerFactory.getLogger(DetectEngineService.class).warn(
                    "Detection state snapshot deferred tenant={}: {}", tenant, failure.getMessage());
        }
    }

    private boolean enqueue(SecurityEvent ev) {
        engineLifecycle.readLock().lock();
        try {
            return engineFor(ev.tenantId(), shardFor(ev)).ingest(ev);
        } finally {
            engineLifecycle.readLock().unlock();
        }
    }

    public List<Alert> recentAlerts() {
        return sink.recent(store.tenant());
    }

    public Map<String, Object> stats() {
        String tenant = store.tenant();
        List<RuleEngine> tenantEngines = engines.entrySet().stream()
                .filter(entry -> entry.getKey().startsWith(tenant + "\u0000shard-"))
                .map(Map.Entry::getValue)
                .toList();
        if (tenantEngines.isEmpty()) tenantEngines = List.of(engineFor(tenant, 0));
        long eventCount = tenantEngines.stream().mapToLong(RuleEngine::eventCount).sum();
        long alertCount = tenantEngines.stream().mapToLong(RuleEngine::alertCount).sum();
        long dropCount = tenantEngines.stream().mapToLong(RuleEngine::dropCount).sum();
        long suppressedCount = tenantEngines.stream().mapToLong(RuleEngine::suppressedCount).sum();
        double queueLoad = tenantEngines.stream().mapToDouble(RuleEngine::queueLoad).max().orElse(0.0);
        List<Map<String, Object>> ruleStats = tenantEngines.stream()
                .flatMap(engine -> engine.ruleStats().stream())
                .toList();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("rules", store.list(tenant).size());
        m.put("eventCount", eventCount);
        m.put("alertCount", alertCount);
        m.put("dropCount", dropCount);
        m.put("suppressedCount", suppressedCount);
        m.put("queueLoad", queueLoad);
        m.put("ruleStats", ruleStats);
        m.put("assignedPartitions", assignedPartitions.get());
        m.put("pendingEvents", stateStore.pendingCount(tenant));
        Map<String, Object> recovery = new LinkedHashMap<>();
        recovery.put("store", stateStore.getClass().getSimpleName());
        String recoveryWindow = stateStore.recoveryWindow();
        recovery.put("replayWindow", recoveryWindow == null ? "unknown" : recoveryWindow);
        m.put("stateRecovery", recovery);
        m.put("stateSnapshots", Map.of(
                "store", snapshotStore == null ? "disabled" : snapshotStore.getClass().getSimpleName(),
                "everyEvents", Math.max(1L, snapshotEveryEvents),
                "shards", effectiveShardCount()));
        m.put("cachedTenantEngines", engines.size());
        return m;
    }
}
