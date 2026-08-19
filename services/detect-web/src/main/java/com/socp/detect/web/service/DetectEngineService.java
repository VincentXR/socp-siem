package com.socp.detect.web.service;

import com.socp.detect.web.engine.AlertForwarder;
import com.socp.detect.web.engine.RecentAlertSink;
import com.socp.detect.web.store.DetectionStateStore;
import com.socp.detect.web.store.InMemoryDetectionStateStore;
import com.socp.detect.web.store.RuleSpecStore;
import com.socp.rule.config.RuleSpec;
import com.socp.rule.engine.RuleEngine;
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
import java.util.concurrent.atomic.AtomicReference;

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
    private final AtomicReference<Set<Integer>> assignedPartitions = new AtomicReference<>(Set.of());

    @org.springframework.beans.factory.annotation.Autowired
    public DetectEngineService(RuleSpecStore store, RecentAlertSink sink, AlertForwarder forwarder,
                               RuleChangePublisher rulePublisher, DetectionStateStore stateStore) {
        this.store = store;
        this.sink = sink;
        this.forwarder = forwarder;
        this.rulePublisher = rulePublisher;
        this.stateStore = stateStore;
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
        engineRef.get().close();
        suppressor.close();
    }

    private RuleEngine buildEngine(List<SecurityEvent> history) {
        List<Rule> rules = store.list().stream()
                .map(RuleSpec::new)
                .filter(spec -> spec.enabled)
                .map(RuleSpec::toRule)
                .toList();
        RuleEngine engine = new RuleEngine(rules, List.of(sink), suppressor);
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
        List<SecurityEvent> history = partitions.isEmpty()
                ? stateStore.recent(Duration.ofHours(24))
                : stateStore.recentForPartitions(partitions, Duration.ofHours(24));
        replaceEngine(buildEngine(history));
    }

    /**
     * Rebuild only from the state owned by the current Kafka assignment. The
     * callback is invoked before records from a new assignment are processed,
     * so a rebalance cannot mix windows from another instance's partitions.
     */
    public synchronized void restoreForPartitions(Set<Integer> partitions) {
        if (partitions == null || partitions.isEmpty()) return;
        Set<Integer> normalized = Set.copyOf(partitions);
        if (normalized.equals(assignedPartitions.get())) return;
        assignedPartitions.set(normalized);
        replaceEngine(buildEngine(stateStore.recentForPartitions(normalized, Duration.ofHours(24))));
    }

    /** Used when Kafka is disabled or for an operational full-state replay. */
    public synchronized void restoreAll() {
        assignedPartitions.set(Set.of());
        replaceEngine(buildEngine(stateStore.recent(Duration.ofHours(24))));
    }

    public Set<Integer> assignedPartitions() {
        return assignedPartitions.get();
    }

    private void replaceEngine(RuleEngine replacement) {
        replacement.start();
        RuleEngine old = engineRef.getAndSet(replacement);
        old.close();
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
        if (!stateStore.recordIfNew(ev)) {
            // At-least-once callers may safely retry the same event id.
            return true;
        }
        boolean accepted = enqueue(ev);
        if (!accepted) stateStore.remove(ev.id());
        return accepted;
    }

    /**
     * Kafka path after the consumer has atomically claimed the event id in the
     * same durable state store. Keeping this separate prevents a second claim
     * while preserving the retry/delete behavior when the bounded queue is full.
     */
    public boolean ingestFromKafka(SecurityEvent ev) {
        return enqueue(ev);
    }

    private boolean enqueue(SecurityEvent ev) {
        return engineRef.get().ingest(ev);
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
        m.put("stateRecovery", Map.of(
                "store", stateStore.getClass().getSimpleName(),
                "replayWindow", stateStore.recoveryWindow()));
        return m;
    }
}
