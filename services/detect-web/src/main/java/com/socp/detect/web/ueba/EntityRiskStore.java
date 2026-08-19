package com.socp.detect.web.ueba;

import com.socp.rule.model.Severity;
import com.socp.rule.score.RiskScorer;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 实体风险画像存储（UEBA 看板数据源）。
 *
 * <p>把散落的单条告警按"实体"（用户 / 主机 / IP）归并成一个持续演进的风险分：
 * <ul>
 *   <li><b>累积</b>：每条告警按其风险分的一定比例注入实体风险，反映"多次可疑行为叠加"；</li>
 *   <li><b>时间衰减</b>：半衰期 6 小时指数衰减，昨天的告警不该让今天的实体一直挂红；</li>
 *   <li><b>画像</b>：记录命中的 ATT&amp;CK 技术分布、触发规则 Top、首末次出现时间。</li>
 * </ul>
 * 分析师因此能直接回答"现在最该看哪个实体"，而不是在几千条同级别告警里翻。
 */
@Component
public class EntityRiskStore {

    /** 风险衰减半衰期 */
    private static final double HALF_LIFE_SECONDS = Duration.ofHours(6).toSeconds();
    /** 单条告警注入实体风险的比例 */
    private static final double INJECT_RATIO = 0.45;
    /** 最多跟踪的实体数，超出淘汰当前风险最低者 */
    private static final int MAX_ENTITIES = 5000;
    /** 近期告警窗口，用于评分的 frequency 分项 */
    private static final Duration RECENT_WINDOW = Duration.ofHours(1);

    private final Map<String, Profile> profiles = new ConcurrentHashMap<>();
    private final Map<String, RiskScorer.Score> appliedAlertScores = new ConcurrentHashMap<>();

    private static final class Profile {
        final String entity;
        double score;
        Instant scoreAt = Instant.now();
        long alerts;
        Instant firstSeen = Instant.now();
        Instant lastSeen = Instant.now();
        Severity maxSeverity = Severity.INFO;
        final Map<String, Integer> mitre = new LinkedHashMap<>();
        final Map<String, Integer> rules = new LinkedHashMap<>();
        final ArrayList<Instant> recent = new ArrayList<>();

        Profile(String entity) {
            this.entity = entity;
        }

        /** 按半衰期把风险衰减到 now */
        double decayed(Instant now) {
            double elapsed = Math.max(0, now.getEpochSecond() - scoreAt.getEpochSecond());
            return score * Math.pow(0.5, elapsed / HALF_LIFE_SECONDS);
        }
    }

    /** 记录一条告警，返回该告警的最终风险评分（含实体频次加权） */
    public RiskScorer.Score record(String entity, Severity severity, String mitre,
                                   String ruleId, String ruleName, int tiHits) {
        return recordInternal(entity, severity, mitre, ruleId, ruleName, tiHits);
    }

    /** Idempotent risk projection for a deterministic Detection alert id. */
    public RiskScorer.Score recordForAlert(String alertId, String entity, Severity severity, String mitre,
                                           String ruleId, String ruleName, int tiHits) {
        if (alertId == null || alertId.isBlank()) {
            return recordInternal(entity, severity, mitre, ruleId, ruleName, tiHits);
        }
        return appliedAlertScores.computeIfAbsent(alertId,
                ignored -> recordInternal(entity, severity, mitre, ruleId, ruleName, tiHits));
    }

    private RiskScorer.Score recordInternal(String entity, Severity severity, String mitre,
                                            String ruleId, String ruleName, int tiHits) {
        Instant now = Instant.now();
        if (entity == null || entity.isBlank()) entity = "unknown";
        Profile p = profiles.computeIfAbsent(entity, Profile::new);
        RiskScorer.Score score;
        synchronized (p) {
            p.recent.removeIf(t -> t.isBefore(now.minus(RECENT_WINDOW)));
            int recentCount = p.recent.size();
            score = RiskScorer.score(severity, mitre, tiHits, recentCount, criticality(entity));

            p.score = Math.min(100, p.decayed(now) + score.score() * INJECT_RATIO);
            p.scoreAt = now;
            p.alerts++;
            p.lastSeen = now;
            p.recent.add(now);
            if (severity != null && severity.level() > p.maxSeverity.level()) p.maxSeverity = severity;
            if (mitre != null && !mitre.isBlank() && !"null".equals(mitre)) p.mitre.merge(mitre, 1, Integer::sum);
            if (ruleName != null && !ruleName.isBlank()) p.rules.merge(ruleName, 1, Integer::sum);
        }
        evictIfNeeded();
        return score;
    }

    /** 核心资产命中观察名单则重要性拉满，否则按普通资产 */
    private static int criticality(String entity) {
        if (com.socp.rule.engine.Watchlists.contains("crown_jewels", entity)) return 3;
        if (com.socp.rule.engine.Watchlists.contains("privileged_accounts", entity)) return 2;
        return 0;
    }

    private void evictIfNeeded() {
        if (profiles.size() <= MAX_ENTITIES) return;
        Instant now = Instant.now();
        profiles.entrySet().stream()
                .sorted(Comparator.comparingDouble(e -> e.getValue().decayed(now)))
                .limit(profiles.size() - MAX_ENTITIES)
                .map(Map.Entry::getKey)
                .toList()
                .forEach(profiles::remove);
    }

    /** 风险 Top N 实体 */
    public List<Map<String, Object>> top(int limit) {
        Instant now = Instant.now();
        return profiles.values().stream()
                .map(p -> toMap(p, now))
                .sorted((a, b) -> Double.compare((Double) b.get("risk"), (Double) a.get("risk")))
                .limit(Math.max(1, limit))
                .toList();
    }

    public Map<String, Object> get(String entity) {
        Profile p = profiles.get(entity);
        return p == null ? null : toMap(p, Instant.now());
    }

    /** 全局摘要：实体总数 + 各风险档位分布 */
    public Map<String, Object> summary() {
        Instant now = Instant.now();
        Map<String, Integer> byLevel = new LinkedHashMap<>();
        for (String l : List.of("CRITICAL", "HIGH", "MEDIUM", "LOW", "INFO")) byLevel.put(l, 0);
        double max = 0;
        for (Profile p : profiles.values()) {
            double d = p.decayed(now);
            max = Math.max(max, d);
            byLevel.merge(RiskScorer.level((int) Math.round(d)), 1, Integer::sum);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("entities", profiles.size());
        m.put("byLevel", byLevel);
        m.put("maxRisk", Math.round(max * 10) / 10.0);
        m.put("halfLifeHours", HALF_LIFE_SECONDS / 3600.0);
        return m;
    }

    private static Map<String, Object> toMap(Profile p, Instant now) {
        double risk;
        Map<String, Integer> mitre;
        Map<String, Integer> rules;
        long alerts;
        Instant first, last;
        Severity maxSev;
        synchronized (p) {
            risk = Math.round(p.decayed(now) * 10) / 10.0;
            mitre = new LinkedHashMap<>(p.mitre);
            rules = new LinkedHashMap<>(p.rules);
            alerts = p.alerts;
            first = p.firstSeen;
            last = p.lastSeen;
            maxSev = p.maxSeverity;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("entity", p.entity);
        m.put("risk", risk);
        m.put("level", RiskScorer.level((int) Math.round(risk)));
        m.put("alerts", alerts);
        m.put("maxSeverity", maxSev.name());
        m.put("firstSeen", first.toString());
        m.put("lastSeen", last.toString());
        m.put("mitre", mitre.entrySet().stream()
                .sorted((a, b) -> b.getValue() - a.getValue()).limit(8)
                .map(e -> Map.of("technique", e.getKey(), "count", e.getValue())).toList());
        m.put("topRules", rules.entrySet().stream()
                .sorted((a, b) -> b.getValue() - a.getValue()).limit(5)
                .map(e -> Map.of("rule", e.getKey(), "count", e.getValue())).toList());
        m.put("critical", com.socp.rule.engine.Watchlists.contains("crown_jewels", p.entity));
        return m;
    }
}
