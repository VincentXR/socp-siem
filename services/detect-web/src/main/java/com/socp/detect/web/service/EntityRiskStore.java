package com.socp.detect.web.service;

import com.socp.detect.web.persistence.entity.EntityRiskAlertEntity;
import com.socp.detect.web.persistence.entity.EntityRiskProfileEntity;
import com.socp.detect.web.persistence.repository.EntityRiskAlertRepository;
import com.socp.detect.web.persistence.repository.EntityRiskProfileRepository;
import com.socp.rule.model.Severity;
import com.socp.rule.score.RiskScorer;
import com.socp.rule.util.Json;
import com.socp.platform.tenant.context.TenantContext;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;

import java.math.BigDecimal;
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

    private static final int MAX_TOP_RESULTS = 500;
    private static final double HALF_LIFE_SECONDS = Duration.ofHours(6).toSeconds();
    private static final double INJECT_RATIO = 0.45;
    private static final Duration RECENT_WINDOW = Duration.ofHours(1);

    private final EntityRiskProfileRepository profiles;
    private final EntityRiskAlertRepository appliedAlerts;
    private final EntityRiskCounterStore counters;

    public EntityRiskStore(EntityRiskProfileRepository profiles, EntityRiskAlertRepository appliedAlerts, EntityRiskCounterStore counters) {
        this.profiles = profiles;
        this.appliedAlerts = appliedAlerts;
        this.counters = counters;
    }

    @Transactional
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
        String tenant = tenant();
        EntityRiskAlertEntity existing = appliedAlerts.findByTenantIdAndAlertId(tenant, alertId).orElse(null);
        if (existing != null) return scoreOf(existing);

        Instant now = Instant.now();
        String key = entity == null || entity.isBlank() ? "unknown" : entity;
        EntityRiskProfileEntity profile = profiles.findForUpdate(tenant, key)
                .orElseGet(() -> newProfile(tenant, key, now));
        // Another transaction may have committed this alert while we waited
        // for the profile lock. Its durable receipt remains authoritative.
        existing = appliedAlerts.findByTenantIdAndAlertId(tenant, alertId).orElse(null);
        if (existing != null) return scoreOf(existing);
        int recent = Math.toIntExact(Math.min(10,
                appliedAlerts.countByTenantIdAndEntityAndCreatedAtAfter(
                        tenant, key, now.minus(RECENT_WINDOW))));
        RiskScorer.Score score = RiskScorer.score(severity, mitre, tiHits, recent, criticality(tenant, key));

        profile.setScore(Math.min(100, decayed(profile, now) + score.score() * INJECT_RATIO));
        profile.setScoreAt(now);
        profile.setAlerts(Math.addExact(profile.getAlerts(), 1));
        profile.setLastSeen(now);
        Severity previous = Severity.valueOf(profile.getMaxSeverity());
        if (severity != null && severity.level() > previous.level()) profile.setMaxSeverity(severity.name());
        // Make a new parent visible to the JDBC FK, still inside this transaction.
        profiles.saveAndFlush(profile);
        if (!profile.isCountersMigrated()) {
            counters.seed(tenant, profile.getStorageId(), legacyCounts(profile.getMitreJson()), legacyCounts(profile.getRulesJson()));
            profile.setCountersMigrated(true);
            profile.setMitreJson("{}");
            profile.setRulesJson("{}");
            profiles.save(profile);
        }
        if (mitre != null && !mitre.isBlank() && !"null".equals(mitre))
            counters.increment(tenant, profile.getStorageId(), "MITRE", mitre);
        if (ruleName != null && !ruleName.isBlank())
            counters.increment(tenant, profile.getStorageId(), "RULE", ruleName);

        EntityRiskAlertEntity applied = new EntityRiskAlertEntity();
        applied.setStorageId(storageId(tenant, alertId));
        applied.setTenantId(tenant);
        applied.setAlertId(alertId);
        applied.setEntity(key);
        applied.setScore(score.score());
        applied.setLevel(score.level());
        applied.setBreakdownJson(json(score.breakdown()));
        applied.setCreatedAt(now);
        appliedAlerts.save(applied);
        return score;
    }

    @Transactional(readOnly = true, timeout = 5, isolation = Isolation.REPEATABLE_READ)
    public List<Map<String, Object>> top(int limit) {
        Instant now = Instant.now();
        int boundedLimit = Math.max(1, Math.min(MAX_TOP_RESULTS, limit));
        String tenant = tenant();
        var selected = profiles.topAt(tenant, now.getEpochSecond(), boundedLimit);
        var snapshot = counters.readTop(tenant, selected.stream().filter(EntityRiskProfileEntity::isCountersMigrated)
                .map(EntityRiskProfileEntity::getStorageId).toList());
        return selected.stream().map(profile -> toMap(profile, now,
                snapshot.getOrDefault(profile.getStorageId(), EntityRiskCounterStore.Counters.EMPTY))).toList();
    }

    @Transactional(readOnly = true, timeout = 5, isolation = Isolation.REPEATABLE_READ)
    public Map<String, Object> get(String entity) {
        String tenant = tenant();
        return profiles.findByTenantIdAndEntity(tenant, entity).map(profile -> {
            var snapshot = counters.readTop(tenant, profile.isCountersMigrated() ? List.of(profile.getStorageId()) : List.of());
            return toMap(profile, Instant.now(), snapshot.getOrDefault(profile.getStorageId(), EntityRiskCounterStore.Counters.EMPTY));
        }).orElse(null);
    }

    @Transactional(readOnly = true, timeout = 5)
    public Map<String, Object> summary() {
        var summary = profiles.summarizeAt(tenant(), Instant.now().getEpochSecond());
        Map<String, Long> byLevel = new LinkedHashMap<>();
        byLevel.put("CRITICAL", summary.getCritical());
        byLevel.put("HIGH", summary.getHigh());
        byLevel.put("MEDIUM", summary.getMedium());
        byLevel.put("LOW", summary.getLow());
        byLevel.put("INFO", summary.getInfo());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("entities", summary.getEntities());
        result.put("byLevel", byLevel);
        result.put("maxRisk", summary.getMaximum());
        result.put("halfLifeHours", HALF_LIFE_SECONDS / 3600.0);
        return result;
    }

    private static EntityRiskProfileEntity newProfile(String tenant, String entity, Instant now) {
        EntityRiskProfileEntity profile = new EntityRiskProfileEntity();
        profile.setStorageId(storageId(tenant, entity));
        profile.setTenantId(tenant);
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

    private static String tenant() {
        return TenantContext.require();
    }

    private static String storageId(String tenant, String localId) {
        return UUID.nameUUIDFromBytes((tenant + "|" + localId)
                .getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
    }

    private static double decayed(EntityRiskProfileEntity profile, Instant now) {
        double elapsed = Math.max(0, now.getEpochSecond() - profile.getScoreAt().getEpochSecond());
        return profile.getScore() * Math.pow(0.5, elapsed / HALF_LIFE_SECONDS);
    }

    private static int criticality(String tenant, String entity) {
        if (com.socp.rule.engine.Watchlists.contains(tenant, "crown_jewels", entity)) return 3;
        if (com.socp.rule.engine.Watchlists.contains(tenant, "privileged_accounts", entity)) return 2;
        return 0;
    }

    private static Map<String, Object> toMap(EntityRiskProfileEntity profile, Instant now, EntityRiskCounterStore.Counters snapshot) {
        double risk = Math.round(decayed(profile, now) * 10) / 10.0;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("entity", profile.getEntity());
        result.put("risk", risk);
        result.put("level", RiskScorer.level((int) Math.round(risk)));
        result.put("alerts", profile.getAlerts());
        result.put("maxSeverity", profile.getMaxSeverity());
        result.put("firstSeen", profile.getFirstSeen().toString());
        result.put("lastSeen", profile.getLastSeen().toString());
        result.put("mitre", profile.isCountersMigrated() ? snapshot.mitre() : ranked(legacyCounts(profile.getMitreJson()), "technique", 8));
        result.put("topRules", profile.isCountersMigrated() ? snapshot.rules() : ranked(legacyCounts(profile.getRulesJson()), "rule", 5));
        result.put("critical", com.socp.rule.engine.Watchlists.contains(
                profile.getTenantId(), "crown_jewels", profile.getEntity()));
        return result;
    }

    private static List<Map<String, Object>> ranked(Map<String, Long> values, String key, int limit) {
        return values.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue(Comparator.reverseOrder()).thenComparing(Map.Entry.comparingByKey()))
                .limit(limit)
                .map(entry -> Map.<String, Object>of(key, entry.getKey(), "count", entry.getValue()))
                .toList();
    }

    private static Map<String, Long> legacyCounts(String value) {
        Map<String, Long> result = new LinkedHashMap<>();
        if (value == null || value.isBlank()) return result;
        Json.parseObject(value).forEach((key, count) -> {
            if (!(count instanceof Number number)) throw new IllegalStateException("Invalid legacy entity-risk count");
            long exact = new BigDecimal(number.toString()).longValueExact();
            if (exact < 0) throw new IllegalStateException("Negative legacy entity-risk count requires repair");
            result.put(key, exact);
        });
        return result;
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
