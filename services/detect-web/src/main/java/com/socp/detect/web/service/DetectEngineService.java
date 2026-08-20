package com.socp.detect.web.service;

import com.socp.detect.web.engine.AlertForwarder;
import com.socp.detect.web.engine.RecentAlertSink;
import com.socp.detect.web.metrics.DetectionPerformanceMetrics;
import com.socp.detect.web.store.DetectionStateStore;
import com.socp.detect.web.store.DetectionEventClaim;
import com.socp.detect.web.store.InMemoryDetectionStateStore;
import com.socp.detect.web.store.RuleSpecStore;
import com.socp.rule.config.RuleSpec;
import com.socp.rule.engine.RuleEngine;
import com.socp.rule.engine.RuleProcessingObserver;
import com.socp.rule.engine.Suppressor;
import com.socp.rule.model.Alert;
import com.socp.rule.model.SecurityEvent;
import com.socp.rule.rules.Rule;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
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
    private final AtomicReference<RuleEngine> engineRef;
    private final RuleChangePublisher rulePublisher;
    private final DetectionStateStore stateStore;
    private final RuleProcessingObserver processingObserver;
    private final AtomicReference<Set<Integer>> assignedPartitions = new AtomicReference<>(Set.of());
    private final ReentrantReadWriteLock engineLifecycle = new ReentrantReadWriteLock(true);

    @org.springframework.beans.factory.annotation.Autowired
    public DetectEngineService(RuleSpecStore store, RecentAlertSink sink, AlertForwarder forwarder,
                               RuleChangePublisher rulePublisher, DetectionStateStore stateStore,
                               DetectionPerformanceMetrics performanceMetrics) {
        this.store = store;
        this.sink = sink;
        this.forwarder = forwarder;
        this.rulePublisher = rulePublisher;
        this.stateStore = stateStore;
        this.processingObserver = performanceMetrics;
        this.engineRef = new AtomicReference<>(buildEngine(List.of()));
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
        this.engineRef = new AtomicReference<>(buildEngine(List.of()));
    }

    /** Unit-test/source compatibility constructor; production uses the JPA journal. */
    public DetectEngineService(RuleSpecStore store, RecentAlertSink sink, AlertForwarder forwarder,
                               RuleChangePublisher rulePublisher) {
        this(store, sink, forwarder, rulePublisher, new InMemoryDetectionStateStore());
    }

    @PostConstruct
    public void start() {
        engineRef.get().start();
    }

    @PreDestroy
    public void stop() {
        engineLifecycle.writeLock().lock();
        try {
            engineRef.get().close();
            suppressor.close();
        } finally {
            engineLifecycle.writeLock().unlock();
        }
    }

    private RuleEngine buildEngine(List<SecurityEvent> history) {
        List<Rule> rules = store.list().stream()
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

    /** 规则热更新：原子替换引擎（旧引擎毒丸退出），无需重启进程 */
    public void reload() {
        Set<Integer> partitions = assignedPartitions.get();
        replaceEngineFromState(partitions);
    }

    /**
     * Rebuild only from the state owned by the current Kafka assignment. The
     * callback is invoked before records from a new assignment are processed,
     * so a rebalance cannot mix windows from another instance's partitions.
     */
    public synchronized void restoreForPartitions(Set<Integer> partitions) {
        restoreForPartitions(partitions, false);
    }

    /** Force a state rebuild after a durable sink failure before retrying. */
    public synchronized void rebuildForPartitions(Set<Integer> partitions) {
        restoreForPartitions(partitions, true);
    }

    private synchronized void restoreForPartitions(Set<Integer> partitions, boolean force) {
        if (partitions == null || partitions.isEmpty()) return;
        Set<Integer> normalized = Set.copyOf(partitions);
        if (!force && normalized.equals(assignedPartitions.get())) return;
        assignedPartitions.set(normalized);
        replaceEngineFromState(normalized);
    }

    /** Used when Kafka is disabled or for an operational full-state replay. */
    public synchronized void restoreAll() {
        assignedPartitions.set(Set.of());
        replaceEngineFromState(Set.of());
    }

    private void restoreState(RuleEngine replacement, Set<Integer> partitions) {
        try {
            if (partitions == null || partitions.isEmpty()) {
                stateStore.replayRecent(Duration.ofHours(24), replacement::restore);
            } else {
                stateStore.replayRecentForPartitions(
                        partitions, Duration.ofHours(24), replacement::restore);
            }
        } catch (Exception ex) {
            org.slf4j.LoggerFactory.getLogger(DetectEngineService.class)
                    .warn("检测状态分页恢复失败，将保留已恢复窗口: {}", ex.getMessage());
        }
    }

    public Set<Integer> assignedPartitions() {
        return assignedPartitions.get();
    }

    private void replaceEngineFromState(Set<Integer> partitions) {
        engineLifecycle.writeLock().lock();
        try {
            // Resolve the rules before closing the live engine so a temporary
            // rule-store outage leaves it available. Once the replacement can
            // be built, stop admission and drain all accepted events before
            // reading the Journal. The new hot state therefore includes every
            // durable completion that happened before the swap.
            RuleEngine replacement = buildEngine(List.of());
            RuleEngine old = engineRef.get();
            old.close();
            restoreState(replacement, partitions);
            replacement.start();
            engineRef.set(replacement);
        } finally {
            engineLifecycle.writeLock().unlock();
        }
    }

    public List<Map<String, Object>> listRules() {
        return store.list();
    }

    public Map<String, Object> contentManifest() {
        return store.contentManifest();
    }

    public Map<String, Object> addRule(Map<String, Object> spec) {
        Map<String, Object> saved = store.save(spec);
        reload();
        rulePublisher.publish(String.valueOf(saved.get("id")), "add");
        return saved;
    }

    public Map<String, Object> updateRule(Map<String, Object> spec) {
        if (store.get(String.valueOf(spec.get("id"))) == null) {
            throw new IllegalArgumentException("规则不存在: " + spec.get("id"));
        }
        Map<String, Object> saved = store.save(spec);
        reload();
        rulePublisher.publish(String.valueOf(saved.get("id")), "update");
        return saved;
    }

    public boolean deleteRule(String id) {
        boolean removed = store.delete(id);
        if (removed) {
            reload();
            rulePublisher.publish(id, "delete");
        }
        return removed;
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
            submission = engineRef.get().submit(ev, true);
        } finally {
            engineLifecycle.readLock().unlock();
        }
        if (!submission.accepted()) {
            if (claim == DetectionEventClaim.NEW) stateStore.remove(ev.id());
            return false;
        }
        submission.completion().whenComplete((ignored, failure) -> {
            if (failure == null) {
                stateStore.markCompleted(ev.id());
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
            return engineRef.get().ingestAndAwait(ev);
        } finally {
            engineLifecycle.readLock().unlock();
        }
    }

    private boolean enqueue(SecurityEvent ev) {
        engineLifecycle.readLock().lock();
        try {
            return engineRef.get().ingest(ev);
        } finally {
            engineLifecycle.readLock().unlock();
        }
    }

    public List<Alert> recentAlerts() {
        return sink.recent();
    }

    public Map<String, Object> stats() {
        RuleEngine e = engineRef.get();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("rules", store.list().size());
        m.put("eventCount", e.eventCount());
        m.put("alertCount", e.alertCount());
        m.put("dropCount", e.dropCount());
        m.put("suppressedCount", e.suppressedCount());
        m.put("queueLoad", e.queueLoad());
        m.put("ruleStats", e.ruleStats());
        m.put("assignedPartitions", assignedPartitions.get());
        m.put("pendingEvents", stateStore.pendingCount());
        m.put("stateRecovery", Map.of(
                "store", stateStore.getClass().getSimpleName(),
                "replayWindow", stateStore.recoveryWindow()));
        return m;
    }
}
