package com.socp.detect.model.service;

import com.socp.detect.model.engine.AlertWindowAggregator;
import com.socp.detect.model.store.AnalyzedEntity;
import com.socp.detect.model.store.AnalyzedRepository;
import com.socp.rule.config.Rules;
import com.socp.rule.model.Alert;
import com.socp.rule.model.SecurityEvent;
import com.socp.rule.model.Severity;
import com.socp.rule.rules.Rule;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 告警二次分析服务（DETECT MODEL 核心）：接收原始告警（HTTP /analyze 与 Kafka
 * socp-alarm-original 同一入口），用 socp-rule 规则引擎做二次关联分析，
 * 命中结果进入 5 分钟滑动窗口聚合器（AlertWindowAggregator），并落库 t_analyzed
 * （研判记录重启不丢，启动时自动恢复）。
 */
@Service
public class AnalyzeService {

    private static final Logger log = LoggerFactory.getLogger(AnalyzeService.class);
    private static final int ANALYZED_MAX = 100_000;

    private final AnalyzedRepository repository;
    private final List<Rule> rules = Rules.defaultRules();
    private final List<Alert> analyzed = new CopyOnWriteArrayList<>();
    private final AlertWindowAggregator windowAggregator;

    public AnalyzeService(AnalyzedRepository repository, AlertWindowAggregator windowAggregator) {
        this.repository = repository;
        this.windowAggregator = windowAggregator;
    }

    @PostConstruct
    void load() {
        try {
            for (AnalyzedEntity e : repository.findAll()) {
                analyzed.add(toAlert(e));
            }
            if (!analyzed.isEmpty()) {
                log.info("已从 t_analyzed 恢复 {} 条二次分析记录", analyzed.size());
            }
        } catch (Exception ex) {
            log.warn("t_analyzed 恢复失败（按空库启动）: {}", ex.toString());
        }
    }

    /** 二次分析：规则引擎评估 + 窗口聚合 + 落库。返回统计结果（HTTP / Kafka 消费复用）。 */
    public Map<String, Object> analyze(Map<String, Object> alarm) {
        String ruleId = String.valueOf(alarm.getOrDefault("ruleId", "UNKNOWN"));
        String entity = String.valueOf(alarm.getOrDefault("entity", ""));
        String msg = String.valueOf(alarm.getOrDefault("message", ""));
        Severity severity = Severity.valueOf(
                String.valueOf(alarm.getOrDefault("severity", "INFO")).toUpperCase());

        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("msg", msg);
        fields.put("src_ip", entity);
        String source = String.valueOf(alarm.getOrDefault("source", "unknown"));
        SecurityEvent ev = new SecurityEvent(Instant.now(), source, entity, msg, fields, severity);

        int matched = 0;
        for (Rule r : rules) {
            r.accept(ev);
            List<Alert> alerts = r.drain();
            if (!alerts.isEmpty()) {
                for (Alert a : alerts) {
                    analyzed.add(a);
                    persist(a);
                }
                matched += alerts.size();
            }
        }
        if (analyzed.size() > ANALYZED_MAX) {
            analyzed.subList(0, analyzed.size() - ANALYZED_MAX).clear();
        }
        if (matched > 0) {
            windowAggregator.record(ruleId, entity, severity.name());
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("inputRuleId", ruleId);
        result.put("entity", entity);
        result.put("analyzedAlerts", matched);
        result.put("totalAnalyzed", analyzed.size());
        return result;
    }

    public List<Alert> analyzed() {
        return List.copyOf(analyzed);
    }

    public Map<String, Object> stats() {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("totalAnalyzed", analyzed.size());
        s.put("rules", rules.size());
        s.put("bySeverity", Map.of(
                "CRITICAL", analyzed.stream().filter(a -> a.severity() == Severity.CRITICAL).count(),
                "HIGH", analyzed.stream().filter(a -> a.severity() == Severity.HIGH).count(),
                "MEDIUM", analyzed.stream().filter(a -> a.severity() == Severity.MEDIUM).count(),
                "LOW", analyzed.stream().filter(a -> a.severity() == Severity.LOW).count(),
                "INFO", analyzed.stream().filter(a -> a.severity() == Severity.INFO).count()));
        s.put("window", windowAggregator.snapshot());
        return s;
    }

    private void persist(Alert a) {
        try {
            repository.save(new AnalyzedEntity(a.id(), a.ruleId(), a.ruleName(),
                    a.severity().name(), truncate(a.message(), 1000), truncate(a.entity(), 250), a.timestamp()));
        } catch (Exception ex) {
            log.warn("t_analyzed 落库失败 alertId={}: {}", a.id(), ex.getMessage());
        }
    }

    private static Alert toAlert(AnalyzedEntity e) {
        Severity sev;
        try {
            sev = Severity.valueOf(e.getSeverity());
        } catch (Exception ex) {
            sev = Severity.INFO;
        }
        return new Alert(e.getAlertId() == null ? "" : e.getAlertId(), e.getTs(),
                e.getRuleId() == null ? "" : e.getRuleId(),
                e.getRuleName() == null ? "" : e.getRuleName(),
                sev, e.getMessage() == null ? "" : e.getMessage(),
                e.getEntity() == null ? "" : e.getEntity(), List.of());
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max);
    }
}
