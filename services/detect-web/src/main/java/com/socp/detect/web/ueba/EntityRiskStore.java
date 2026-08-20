package com.socp.detect.web.ueba;

import com.socp.rule.model.Severity;
import com.socp.rule.score.RiskScorer;
import com.socp.rule.util.Json;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Shared, durable entity-risk projection used by every Detection instance. */
@Component
public class EntityRiskStore {

    private static final double HALF_LIFE_SECONDS = Duration.ofHours(6).toSeconds();
    private static final double INJECT_RATIO = 0.45;
    private static final Duration RECENT_WINDOW = Duration.ofHours(1);

    private final EntityRiskProfileRepository profiles;
    private final EntityRiskAlertRepository appliedAlerts;

    public EntityRiskStore(EntityRiskProfileRepository profiles, EntityRiskAlertRepository appliedAlerts) {
        this.profiles = profiles;
        this.appliedAlerts = appliedAlerts;
    }

    public RiskScorer.Score record(String entity, Severity severity, String mitre,
                                   String ruleId, String ruleName, int tiHits) {
        return recordForAlert("unkeyed-" + UUID.randomUUID(), entity, severity, mitre,
                ruleId, ruleName, tiHits);
    }

    /**
     * Atomically project one deterministic Detection alert. The alert primary
     * key is the idempotency boundary and the profile row is locked while its
     * decayed score and counters are advanced.
     */
    @Transactional
    public RiskScorer.Score recordForAlert(String alertId, String entity, Severity severity, String mitre,
                                           String ruleId, String ruleName, int tiHits) {
        if (alertId == null || alertId.isBlank()) alertId = "unkeyed-" + UUID.randomUUID();
        EntityRiskAlertEntity existing = appliedAlerts.findById(alertId).orElse(null);
        if (existing != null) return scoreOf(existing);

        Instant now = Instant.now();
        String key = entity == null || entity.isBlank() ? "unknown" : entity;
        EntityRiskProfileEntity profile = profiles.findForUpdate(key).orElseGet(() -> newProfile(key, now));
        int recent = Math.toIntExact(Math.min(10,
                appliedAlerts.countByEntityAndCreatedAtAfter(key, now.minus(RECENT_WINDOW))));
        RiskScorer.Score score = RiskScorer.score(severity, mitre, tiHits, recent, criticality(key));

        profile.setScore(Math.min(100, decayed(profile, now) + score.score() * INJECT_RATIO));
        profile.setScoreAt(now);
        profile.setAlerts(profile.getAlerts() + 1);
        profile.setLastSeen(now);
        Severity previous = Severity.valueOf(profile.getMaxSeverity());
        if (severity != null && severity.level() > previous.level()) profile.setMaxSeverity(severity.name());
        Map<String, Integer> mitreCounts = counts(profile.getMitreJson());
        Map<String, Integer> ruleCounts = counts(profile.getRulesJson());
        if (mitre != null && !mitre.isBlank() && !"null".equals(mitre)) mitreCounts.merge(mitre, 1, Integer::sum);
        if (ruleName != null && !ruleName.isBlank()) ruleCounts.merge(ruleName, 1, Integer::sum);
        profile.setMitreJson(json(mitreCounts));
        profile.setRulesJson(json(ruleCounts));
        profiles.save(profile);

        EntityRiskAlertEntity applied = new EntityRiskAlertEntity();
        applied.setAlertId(alertId);
        applied.setEntity(key);
        applied.setScore(score.score());
        applied.setLevel(score.level());
        applied.setBreakdownJson(json(score.breakdown()));
        applied.setCreatedAt(now);
        appliedAlerts.save(applied);
        return score;
    }

    public List<Map<String, Object>> top(int limit) {
        Instant now = Instant.now();
        return profiles.findAll().stream()
                .map(profile -> toMap(profile, now))
                .sorted((a, b) -> Double.compare((Double) b.get("risk"), (Double) a.get("risk")))
                .limit(Math.max(1, limit))
                .toList();
    }

    public Map<String, Object> get(String entity) {
        return profiles.findById(entity).map(profile -> toMap(profile, Instant.now())).orElse(null);
    }

    public Map<String, Object> summary() {
        Instant now = Instant.now();
        List<EntityRiskProfileEntity> all = profiles.findAll();
        Map<String, Integer> byLevel = new LinkedHashMap<>();
        for (String level : List.of("CRITICAL", "HIGH", "MEDIUM", "LOW", "INFO")) byLevel.put(level, 0);
        double max = 0;
        for (EntityRiskProfileEntity profile : all) {
            double risk = decayed(profile, now);
            max = Math.max(max, risk);
            byLevel.merge(RiskScorer.level((int) Math.round(risk)), 1, Integer::sum);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("entities", all.size());
        result.put("byLevel", byLevel);
        result.put("maxRisk", Math.round(max * 10) / 10.0);
        result.put("halfLifeHours", HALF_LIFE_SECONDS / 3600.0);
        return result;
    }

    private static EntityRiskProfileEntity newProfile(String entity, Instant now) {
        EntityRiskProfileEntity profile = new EntityRiskProfileEntity();
        profile.setEntity(entity);
        profile.setScore(0);
        profile.setScoreAt(now);
        profile.setAlerts(0);
        profile.setFirstSeen(now);
        profile.setLastSeen(now);
        profile.setMaxSeverity(Severity.INFO.name());
        profile.setMitreJson("{}");
        profile.setRulesJson("{}");
        return profile;
    }

    private static double decayed(EntityRiskProfileEntity profile, Instant now) {
        double elapsed = Math.max(0, now.getEpochSecond() - profile.getScoreAt().getEpochSecond());
        return profile.getScore() * Math.pow(0.5, elapsed / HALF_LIFE_SECONDS);
    }

    private static int criticality(String entity) {
        if (com.socp.rule.engine.Watchlists.contains("crown_jewels", entity)) return 3;
        if (com.socp.rule.engine.Watchlists.contains("privileged_accounts", entity)) return 2;
        return 0;
    }

    private static Map<String, Object> toMap(EntityRiskProfileEntity profile, Instant now) {
        double risk = Math.round(decayed(profile, now) * 10) / 10.0;
        Map<String, Integer> mitre = counts(profile.getMitreJson());
        Map<String, Integer> rules = counts(profile.getRulesJson());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("entity", profile.getEntity());
        result.put("risk", risk);
        result.put("level", RiskScorer.level((int) Math.round(risk)));
        result.put("alerts", profile.getAlerts());
        result.put("maxSeverity", profile.getMaxSeverity());
        result.put("firstSeen", profile.getFirstSeen().toString());
        result.put("lastSeen", profile.getLastSeen().toString());
        result.put("mitre", ranked(mitre, "technique", 8));
        result.put("topRules", ranked(rules, "rule", 5));
        result.put("critical", com.socp.rule.engine.Watchlists.contains("crown_jewels", profile.getEntity()));
        return result;
    }

    private static List<Map<String, Object>> ranked(Map<String, Integer> values, String key, int limit) {
        return values.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue(Comparator.reverseOrder()))
                .limit(limit)
                .map(entry -> Map.<String, Object>of(key, entry.getKey(), "count", entry.getValue()))
                .toList();
    }

    private static RiskScorer.Score scoreOf(EntityRiskAlertEntity entity) {
        return new RiskScorer.Score(entity.getScore(), entity.getLevel(), counts(entity.getBreakdownJson()));
    }

    private static Map<String, Integer> counts(String value) {
        Map<String, Integer> result = new LinkedHashMap<>();
        if (value == null || value.isBlank()) return result;
        Json.parseObject(value).forEach((key, item) -> {
            if (item instanceof Number number) result.put(key, number.intValue());
        });
        return result;
    }

    private static String json(Map<String, Integer> value) {
        try {
            return Json.mapper().writeValueAsString(value);
        } catch (Exception error) {
            throw new IllegalStateException("risk projection JSON serialization failed", error);
        }
    }
}
