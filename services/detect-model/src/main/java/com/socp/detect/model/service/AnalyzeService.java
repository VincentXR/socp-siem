package com.socp.detect.model.service;

import com.socp.detect.model.engine.AlertWindowAggregator;
import com.socp.detect.model.persistence.entity.AnalyzedEntity;
import com.socp.detect.model.persistence.repository.AnalyzedRepository;
import com.socp.detect.model.persistence.store.AnalysisReceiptStore;
import com.socp.platform.tenant.context.TenantContext;
import com.socp.rule.config.Rules;
import com.socp.rule.model.Alert;
import com.socp.rule.model.SecurityEvent;
import com.socp.rule.model.Severity;
import com.socp.rule.rules.Rule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Tenant-scoped secondary alert analysis backed by a durable projection. */
@Service
public class AnalyzeService {

    private static final Logger log = LoggerFactory.getLogger(AnalyzeService.class);
    private static final int MAX_PAGE_SIZE = 200;
    private static final int DEFAULT_RULE_COUNT = Rules.defaultRules().size();

    private final AnalyzedRepository repository;
    private final Map<String, TenantRules> rulesByTenant = new ConcurrentHashMap<>();
    private final AlertWindowAggregator windowAggregator;
    private final AnalysisReceiptStore receiptStore;
    private final java.util.concurrent.locks.ReentrantReadWriteLock ruleStateLifecycle =
            new java.util.concurrent.locks.ReentrantReadWriteLock(true);

    @Value("${socp.detect.model.retention:30d}")
    private Duration retention = Duration.ofDays(30);

    @Value("${socp.detect.model.rule-state-idle-ttl-ms:1800000}")
    private long ruleStateIdleTtlMs = 30 * 60 * 1000L;

    @Value("${socp.detect.model.rule-state-max-tenants:1000}")
    private int maxRuleStateTenants = 1000;

    @Value("${socp.detect.model.analyzer-version:v1}")
    private String analyzerVersion = "v1";

    public AnalyzeService(AnalyzedRepository repository, AlertWindowAggregator windowAggregator) {
        this(repository, windowAggregator, new AnalysisReceiptStore());
    }

    @org.springframework.beans.factory.annotation.Autowired
    public AnalyzeService(AnalyzedRepository repository, AlertWindowAggregator windowAggregator,
                          AnalysisReceiptStore receiptStore) {
        this.repository = repository;
        this.windowAggregator = windowAggregator;
        this.receiptStore = receiptStore;
    }

    @Transactional
    public Map<String, Object> analyze(Map<String, Object> alarm) {
        String tenant = tenant(alarm);
        String ruleId = String.valueOf(alarm.getOrDefault("ruleId", "UNKNOWN"));
        String entity = String.valueOf(alarm.getOrDefault("entity", ""));
        String message = String.valueOf(alarm.getOrDefault("message", ""));
        Severity severity = Severity.valueOf(
                String.valueOf(alarm.getOrDefault("severity", "INFO")).toUpperCase());
        String sourceAlarmId = text(alarm.get("sourceAlarmId"));
        if (sourceAlarmId == null) sourceAlarmId = text(alarm.get("source_alarm_id"));
        sourceAlarmId = boundedKey(sourceAlarmId, 128, "sourceAlarmId");
        String version = text(alarm.get("analyzerVersion"));
        if (version == null) version = text(alarm.get("analyzer_version"));
        if (version == null) version = analyzerVersion == null || analyzerVersion.isBlank() ? "v1" : analyzerVersion;
        version = boundedKey(version, 128, "analyzerVersion");

        if (sourceAlarmId != null && !receiptStore.claim(tenant, sourceAlarmId, version)) {
            return duplicateResult(ruleId, entity, tenant, sourceAlarmId, version);
        }

        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("msg", message);
        fields.put("src_ip", entity);
        fields.put("tenant_id", tenant);
        String source = String.valueOf(alarm.getOrDefault("source", "unknown"));
        String eventId = sourceAlarmId == null
                ? UUID.randomUUID().toString()
                : UUID.nameUUIDFromBytes((tenant + "|" + sourceAlarmId + "|" + version)
                .getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
        SecurityEvent event = new SecurityEvent(eventId, eventTimestamp(alarm), source, entity, message, fields, severity);

        // 告警风暴智能抑制：同实体同规则在一分钟内超出 50 次时启动收敛
        String stormKey = tenant + ":" + ruleId + ":" + entity + ":" + (System.currentTimeMillis() / 60000);
        long count = stormCounters.compute(stormKey, (k, v) -> v == null ? 1L : v + 1);
        boolean suppressed = count > 50;

        List<Alert> alerts = evaluateRules(tenant, event);
        if (!suppressed) {
            for (Alert alert : alerts) persist(tenant, alert);
        }
        int matched = alerts.size();
        if (matched > 0) windowAggregator.record(tenant, ruleId, entity, severity.name());
        if (sourceAlarmId != null) receiptStore.complete(tenant, sourceAlarmId, version, matched);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("inputRuleId", ruleId);
        result.put("entity", entity);
        result.put("analyzedAlerts", matched);
        result.put("stormSuppressed", suppressed);
        if (sourceAlarmId != null) {
            result.put("sourceAlarmId", sourceAlarmId);
            result.put("analyzerVersion", version);
            result.put("duplicate", false);
        }
        result.put("totalAnalyzed", repository.countByTenantId(tenant));
        return result;
    }

    private Map<String, Object> duplicateResult(String ruleId, String entity, String tenant,
                                                 String sourceAlarmId, String version) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("inputRuleId", ruleId);
        result.put("entity", entity);
        result.put("analyzedAlerts", 0);
        result.put("stormSuppressed", false);
        result.put("sourceAlarmId", sourceAlarmId);
        result.put("analyzerVersion", version);
        result.put("duplicate", true);
        result.put("totalAnalyzed", repository.countByTenantId(tenant));
        return result;
    }

    private final Map<String, Long> stormCounters = new ConcurrentHashMap<>();

    @Transactional(readOnly = true)
    public AnalyzedPage analyzed(int page, int size) {
        int safePage = Math.max(0, page);
        int safeSize = Math.max(1, Math.min(MAX_PAGE_SIZE, size));
        var result = repository.findByTenantId(currentTenant(), PageRequest.of(
                safePage, safeSize, Sort.by(Sort.Order.desc("ts"), Sort.Order.desc("id"))));
        return new AnalyzedPage(result.getContent().stream().map(AnalyzeService::toAlert).toList(),
                result.getTotalElements(), result.getNumber(), result.getSize(), result.getTotalPages());
    }

    @Transactional(readOnly = true)
    public Map<String, Object> stats() {
        String tenant = currentTenant();
        Map<String, Long> bySeverity = new LinkedHashMap<>();
        for (Severity severity : Severity.values()) bySeverity.put(severity.name(), 0L);
        for (Object[] row : repository.countBySeverity(tenant)) {
            if (row == null || row.length < 2 || row[0] == null || row[1] == null) continue;
            bySeverity.put(String.valueOf(row[0]), ((Number) row[1]).longValue());
        }
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("totalAnalyzed", repository.countByTenantId(tenant));
        stats.put("rules", DEFAULT_RULE_COUNT);
        stats.put("bySeverity", bySeverity);
        stats.put("window", windowAggregator.snapshot(tenant));
        return stats;
    }

    private void persist(String tenant, Alert alert) {
        repository.save(new AnalyzedEntity(tenant, alert.id(), alert.ruleId(), alert.ruleName(),
                alert.severity().name(), truncate(alert.message(), 1000),
                truncate(alert.entity(), 250), alert.timestamp()));
    }

    @Scheduled(fixedDelayString = "${socp.detect.model.cleanup-interval-ms:3600000}",
            initialDelayString = "${socp.detect.model.cleanup-initial-delay-ms:60000}")
    @Transactional
    public void cleanupExpired() {
        Duration safeRetention = retention == null || retention.isNegative() || retention.isZero()
                ? Duration.ofDays(30) : retention;
        int removed = repository.deleteBefore(Instant.now().minus(safeRetention));
        if (removed > 0) log.info("Removed {} expired secondary analysis records", removed);
    }

    @Scheduled(fixedDelayString = "${socp.detect.model.rule-state-cleanup-interval-ms:60000}")
    void evictIdleRuleState() {
        ruleStateLifecycle.writeLock().lock();
        try {
            long now = System.currentTimeMillis();
            long safeTtl = Math.max(60_000L, ruleStateIdleTtlMs);
            rulesByTenant.entrySet().removeIf(entry -> now - entry.getValue().lastAccessMillis > safeTtl);
            int excess = rulesByTenant.size() - Math.max(1, maxRuleStateTenants);
            if (excess > 0) {
                rulesByTenant.entrySet().stream()
                        .sorted(Map.Entry.comparingByValue(
                                java.util.Comparator.comparingLong(value -> value.lastAccessMillis)))
                        .limit(excess)
                        .forEach(entry -> rulesByTenant.remove(entry.getKey(), entry.getValue()));
            }
        } finally {
            ruleStateLifecycle.writeLock().unlock();
        }
    }

    private List<Alert> evaluateRules(String tenant, SecurityEvent event) {
        ruleStateLifecycle.readLock().lock();
        try {
            TenantRules rules = rulesByTenant.computeIfAbsent(requireTenant(tenant),
                    ignored -> new TenantRules(Rules.defaultRules()));
            rules.touch();
            return rules.evaluate(event);
        } finally {
            ruleStateLifecycle.readLock().unlock();
        }
    }

    int cachedTenantRuleStates() {
        return rulesByTenant.size();
    }

    private static String tenant(Map<String, Object> alarm) {
        String context = TenantContext.get();
        if (context != null && !context.isBlank()) {
            if (!TenantContext.isValid(context)) throw new IllegalStateException("invalid tenant context");
            return context;
        }
        Object carried = alarm.get("tenantId");
        if (carried == null) carried = alarm.get("tenant_id");
        if (carried == null || String.valueOf(carried).isBlank()) return TenantContext.require();
        String tenant = String.valueOf(carried).trim();
        if (!TenantContext.isValid(tenant)) throw new IllegalArgumentException("invalid alarm tenant");
        return tenant;
    }

    private static String currentTenant() {
        return TenantContext.require();
    }

    private static String requireTenant(String tenant) {
        if (!TenantContext.isValid(tenant)) throw new IllegalArgumentException("invalid tenant");
        return tenant;
    }

    private static Alert toAlert(AnalyzedEntity entity) {
        Severity severity;
        try {
            severity = Severity.valueOf(entity.getSeverity());
        } catch (RuntimeException ignored) {
            severity = Severity.INFO;
        }
        return new Alert(entity.getAlertId() == null ? "" : entity.getAlertId(), entity.getTs(),
                entity.getRuleId() == null ? "" : entity.getRuleId(),
                entity.getRuleName() == null ? "" : entity.getRuleName(), severity,
                entity.getMessage() == null ? "" : entity.getMessage(),
                entity.getEntity() == null ? "" : entity.getEntity(), List.of());
    }

    private static String truncate(String value, int max) {
        if (value == null) return "";
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static String text(Object value) {
        if (value == null) return null;
        String result = String.valueOf(value).trim();
        return result.isBlank() || "null".equalsIgnoreCase(result) ? null : result;
    }

    private static String boundedKey(String value, int max, String field) {
        if (value != null && value.length() > max) {
            throw new IllegalArgumentException(field + " exceeds " + max + " characters");
        }
        return value;
    }

    private static Instant eventTimestamp(Map<String, Object> alarm) {
        String raw = text(alarm.get("timestamp"));
        if (raw != null) {
            try {
                return Instant.parse(raw);
            } catch (RuntimeException ignored) {
                // Invalid optional timestamp is treated as ingestion time; the
                // request contract still validates all required fields.
            }
        }
        return Instant.now();
    }

    private static final class TenantRules {
        private final List<Rule> rules;
        private volatile long lastAccessMillis = System.currentTimeMillis();

        private TenantRules(List<Rule> rules) {
            this.rules = rules;
        }

        private synchronized List<Alert> evaluate(SecurityEvent event) {
            List<Alert> emitted = new java.util.ArrayList<>();
            for (Rule rule : rules) {
                rule.accept(event);
                emitted.addAll(rule.drain());
            }
            touch();
            return List.copyOf(emitted);
        }

        private void touch() {
            lastAccessMillis = System.currentTimeMillis();
        }
    }

    public record AnalyzedPage(List<Alert> items, long total, int page, int size, int totalPages) {
    }
}
