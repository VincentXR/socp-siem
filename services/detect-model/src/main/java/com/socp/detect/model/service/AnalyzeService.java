package com.socp.detect.model.service;

import com.socp.detect.model.engine.AlertWindowAggregator;
import com.socp.detect.model.store.AnalyzedEntity;
import com.socp.detect.model.store.AnalyzedRepository;
import com.socp.platform.tenant.TenantContext;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/** Tenant-scoped secondary alert analysis and durable projection. */
@Service
public class AnalyzeService {

    private static final Logger log = LoggerFactory.getLogger(AnalyzeService.class);
    private static final int ANALYZED_MAX = 100_000;

    private final AnalyzedRepository repository;
    private final Map<String, List<Rule>> rulesByTenant = new ConcurrentHashMap<>();
    private final Map<String, CopyOnWriteArrayList<Alert>> analyzedByTenant = new ConcurrentHashMap<>();
    private final AlertWindowAggregator windowAggregator;

    public AnalyzeService(AnalyzedRepository repository, AlertWindowAggregator windowAggregator) {
        this.repository = repository;
        this.windowAggregator = windowAggregator;
    }

    @PostConstruct
    void load() {
        try {
            for (AnalyzedEntity entity : repository.findAll()) {
                analyzedFor(entity.getTenantId()).add(toAlert(entity));
            }
            long loaded = analyzedByTenant.values().stream().mapToLong(List::size).sum();
            if (loaded > 0) log.info("Restored {} tenant-scoped secondary analysis records", loaded);
        } catch (RuntimeException failure) {
            log.warn("Secondary analysis restore failed; starting with an empty cache: {}",
                    failure.getMessage());
        }
    }

    public Map<String, Object> analyze(Map<String, Object> alarm) {
        String tenant = tenant(alarm);
        String ruleId = String.valueOf(alarm.getOrDefault("ruleId", "UNKNOWN"));
        String entity = String.valueOf(alarm.getOrDefault("entity", ""));
        String message = String.valueOf(alarm.getOrDefault("message", ""));
        Severity severity = Severity.valueOf(
                String.valueOf(alarm.getOrDefault("severity", "INFO")).toUpperCase());

        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("msg", message);
        fields.put("src_ip", entity);
        fields.put("tenant_id", tenant);
        String source = String.valueOf(alarm.getOrDefault("source", "unknown"));
        SecurityEvent event = new SecurityEvent(Instant.now(), source, entity, message, fields, severity);

        CopyOnWriteArrayList<Alert> analyzed = analyzedFor(tenant);
        int matched = 0;
        for (Rule rule : rulesFor(tenant)) {
            rule.accept(event);
            List<Alert> alerts = rule.drain();
            for (Alert alert : alerts) {
                analyzed.add(alert);
                persist(tenant, alert);
            }
            matched += alerts.size();
        }
        if (analyzed.size() > ANALYZED_MAX) {
            analyzed.subList(0, analyzed.size() - ANALYZED_MAX).clear();
        }
        if (matched > 0) windowAggregator.record(tenant, ruleId, entity, severity.name());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("inputRuleId", ruleId);
        result.put("entity", entity);
        result.put("analyzedAlerts", matched);
        result.put("totalAnalyzed", analyzed.size());
        return result;
    }

    public List<Alert> analyzed() {
        return List.copyOf(analyzedFor(currentTenant()));
    }

    public Map<String, Object> stats() {
        String tenant = currentTenant();
        List<Alert> analyzed = analyzedFor(tenant);
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("totalAnalyzed", analyzed.size());
        stats.put("rules", rulesFor(tenant).size());
        stats.put("bySeverity", Map.of(
                "CRITICAL", count(analyzed, Severity.CRITICAL),
                "HIGH", count(analyzed, Severity.HIGH),
                "MEDIUM", count(analyzed, Severity.MEDIUM),
                "LOW", count(analyzed, Severity.LOW),
                "INFO", count(analyzed, Severity.INFO)));
        stats.put("window", windowAggregator.snapshot(tenant));
        return stats;
    }

    private void persist(String tenant, Alert alert) {
        try {
            repository.save(new AnalyzedEntity(tenant, alert.id(), alert.ruleId(), alert.ruleName(),
                    alert.severity().name(), truncate(alert.message(), 1000),
                    truncate(alert.entity(), 250), alert.timestamp()));
        } catch (RuntimeException failure) {
            log.warn("Secondary analysis persistence failed alertId={}: {}",
                    alert.id(), failure.getMessage());
        }
    }

    private List<Rule> rulesFor(String tenant) {
        return rulesByTenant.computeIfAbsent(tenant, ignored -> Rules.defaultRules());
    }

    private CopyOnWriteArrayList<Alert> analyzedFor(String tenant) {
        String normalized = normalizeTenant(tenant);
        return analyzedByTenant.computeIfAbsent(normalized, ignored -> new CopyOnWriteArrayList<>());
    }

    private static long count(List<Alert> alerts, Severity severity) {
        return alerts.stream().filter(alert -> alert.severity() == severity).count();
    }

    private static String tenant(Map<String, Object> alarm) {
        String context = TenantContext.get();
        if (context != null && !context.isBlank()) return context;
        Object carried = alarm.get("tenantId");
        if (carried == null) carried = alarm.get("tenant_id");
        return normalizeTenant(carried == null ? null : String.valueOf(carried));
    }

    private static String currentTenant() {
        return normalizeTenant(TenantContext.get());
    }

    private static String normalizeTenant(String tenant) {
        return tenant == null || tenant.isBlank() ? "default" : tenant;
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
}
