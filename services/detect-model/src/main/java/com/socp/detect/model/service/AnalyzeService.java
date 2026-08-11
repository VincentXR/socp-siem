package com.socp.detect.model.service;

import com.socp.detect.model.engine.AlertWindowAggregator;
import com.socp.rule.config.Rules;
import com.socp.rule.model.Alert;
import com.socp.rule.model.SecurityEvent;
import com.socp.rule.model.Severity;
import com.socp.rule.rules.Rule;
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
 * 命中结果进入 5 分钟滑动窗口聚合器（AlertWindowAggregator）。
 *
 * <p>analyzed 列表为进程内快照（有界 10 万条），/analyzed /stats 直接读它。
 */
@Service
public class AnalyzeService {

    private static final Logger log = LoggerFactory.getLogger(AnalyzeService.class);
    private static final int ANALYZED_MAX = 100_000;

    private final List<Rule> rules = Rules.defaultRules();
    private final List<Alert> analyzed = new CopyOnWriteArrayList<>();
    private final AlertWindowAggregator windowAggregator;

    public AnalyzeService(AlertWindowAggregator windowAggregator) {
        this.windowAggregator = windowAggregator;
    }

    /** 二次分析：规则引擎评估 + 窗口聚合。返回统计结果（HTTP 响应体 / Kafka 消费日志复用）。 */
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
                analyzed.addAll(alerts);
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
}
