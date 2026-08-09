package com.socp.detect.model.api;

import com.socp.detect.model.engine.AlertWindowAggregator;
import com.socp.rule.config.Rules;
import com.socp.rule.model.Alert;
import com.socp.rule.model.SecurityEvent;
import com.socp.rule.model.Severity;
import com.socp.rule.rules.Rule;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * DETECT Model 窗口聚合 API——对告警做二次关联分析。
 *
 * <p>生产环境从 Kafka socp-detect-original-alarm 消费告警，做窗口聚合后写入
 * socp-detect-analyzed-alarm 主题 + PG t_alarm_hist + AGE 关联图。
 * 当前为内存态：接收原始告警 → 用 socp-rule 规则引擎做二次分析 →
 * AlertWindowAggregator 做 5 分钟滑动窗口聚合。
 */
@RestController
@RequestMapping("/api/v1")
public class ModelController {

    private final List<Rule> rules = Rules.defaultRules();
    private final List<Alert> analyzed = new CopyOnWriteArrayList<>();
    private final AlertWindowAggregator windowAggregator;

    public ModelController(AlertWindowAggregator windowAggregator) {
        this.windowAggregator = windowAggregator;
    }

    /**
     * 接收原始告警做二次分析（模拟 Kafka 消费）。
     */
    @PostMapping("/analyze")
    public Map<String, Object> analyze(@RequestBody Map<String, Object> alarm) {
        String ruleId = String.valueOf(alarm.getOrDefault("ruleId", "UNKNOWN"));
        String entity = String.valueOf(alarm.getOrDefault("entity", ""));
        String msg = String.valueOf(alarm.getOrDefault("message", ""));
        Severity severity = Severity.valueOf(
                String.valueOf(alarm.getOrDefault("severity", "INFO")).toUpperCase());

        // 把告警转回 SecurityEvent 供规则引擎做二次关联
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
        // 写入 5 分钟滑动窗口聚合器（按输入 ruleId / 实体 / 级别）
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

    @GetMapping("/analyzed")
    public List<Alert> analyzed() {
        return List.copyOf(analyzed);
    }

    @GetMapping("/stats")
    public Map<String, Object> stats() {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("totalAnalyzed", analyzed.size());
        s.put("rules", rules.size());
        s.put("bySeverity", Map.of(
                "CRITICAL", analyzed.stream().filter(a -> a.severity() == Severity.CRITICAL).count(),
                "HIGH", analyzed.stream().filter(a -> a.severity() == Severity.HIGH).count(),
                "MEDIUM", analyzed.stream().filter(a -> a.severity() == Severity.MEDIUM).count(),
                "LOW", analyzed.stream().filter(a -> a.severity() == Severity.LOW).count()
        ));
        return s;
    }

    /** 5 分钟滑动窗口聚合：按规则/实体/级别命中数 + 分钟级趋势。 */
    @GetMapping("/window")
    public Map<String, Object> window() {
        return windowAggregator.snapshot();
    }

    /** 分钟级趋势（最近 5 分钟命中数）。 */
    @GetMapping("/window/trend")
    public List<Map<String, Object>> windowTrend() {
        return windowAggregator.trend();
    }
}
