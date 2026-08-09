package com.socp.detect.web.service;

import com.socp.detect.web.engine.AlertForwarder;
import com.socp.detect.web.engine.RecentAlertSink;
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

    public DetectEngineService(RuleSpecStore store, RecentAlertSink sink, AlertForwarder forwarder) {
        this.store = store;
        this.sink = sink;
        this.forwarder = forwarder;
        this.engineRef = new AtomicReference<>(buildEngine());
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

    private RuleEngine buildEngine() {
        List<Rule> rules = store.list().stream()
                .map(RuleSpec::new)
                .filter(spec -> spec.enabled)
                .map(RuleSpec::toRule)
                .toList();
        return new RuleEngine(rules, List.of(sink), suppressor);
    }

    /** 规则热更新：原子替换引擎（旧引擎毒丸退出），无需重启进程 */
    public void reload() {
        RuleEngine old = engineRef.getAndSet(buildEngine());
        old.close();
        engineRef.get().start();
    }

    public List<Map<String, Object>> listRules() {
        return store.list();
    }

    public Map<String, Object> addRule(Map<String, Object> spec) {
        Map<String, Object> saved = store.save(spec);
        reload();
        return saved;
    }

    public Map<String, Object> updateRule(Map<String, Object> spec) {
        if (store.get(String.valueOf(spec.get("id"))) == null) {
            throw new IllegalArgumentException("规则不存在: " + spec.get("id"));
        }
        Map<String, Object> saved = store.save(spec);
        reload();
        return saved;
    }

    public boolean deleteRule(String id) {
        boolean removed = store.delete(id);
        if (removed) reload();
        return removed;
    }

    /** 事件摄取：队列满回 false（接入端据此回 503 + Retry-After） */
    public boolean ingest(SecurityEvent ev) {
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
        return m;
    }
}
