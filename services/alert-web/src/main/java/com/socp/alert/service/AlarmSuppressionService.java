package com.socp.alert.service;

import com.socp.alert.persistence.entity.AlarmSuppressionEntity;
import com.socp.alert.persistence.repository.AlarmSuppressionRepository;
import com.socp.platform.error.exception.ApiException;
import com.socp.platform.tenant.context.TenantContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 静默窗口的唯一权威：判定与记录都走数据库，因此任一 alert-web 副本对同一条告警
 * 给出一致的结论，重启也不会撤销分析师的决策。
 */
@Service
public class AlarmSuppressionService {

    /** Rule-wide scope marker: an empty entity key suppresses every entity of the rule. */
    public static final String ANY_ENTITY = "";
    public static final String ORIGIN_FALSE_POSITIVE = "FALSE_POSITIVE";
    public static final String ORIGIN_MANUAL = "MANUAL";

    static final Duration DEFAULT_WINDOW = Duration.ofHours(24);
    private static final Duration MAX_WINDOW = Duration.ofDays(30);
    private static final int MAX_ACTIVE_WINDOWS = 1_000;
    private static final List<String> ORIGINS = List.of(ORIGIN_FALSE_POSITIVE, ORIGIN_MANUAL);

    private final AlarmSuppressionRepository repository;
    private final AlarmSuppressionLock lock;

    public AlarmSuppressionService(AlarmSuppressionRepository repository, AlarmSuppressionLock lock) {
        this.repository = repository;
        this.lock = lock;
    }

    /** Read-only decision used by the alarm creation path. */
    @Transactional(readOnly = true)
    public boolean suppresses(String tenant, String ruleId, String entity) {
        if (ruleId == null || ruleId.isBlank()) {
            return false;
        }
        return !repository.findActive(tenant, ruleId.trim(), scopes(entity), Instant.now()).isEmpty();
    }

    @Transactional
    public Map<String, Object> record(String ruleId, String entity, String origin, String reason,
                                      String alarmId, String actor, Long windowSeconds, boolean ruleWide) {
        String tenant = TenantContext.require();
        String normalizedRule = require(ruleId, "ruleId", 255);
        String normalizedEntity = optional(entity, 255);
        if (ruleWide == (normalizedEntity != null)) {
            throw ApiException.badRequest("Specify an entity, or explicitly confirm ruleWide with no entity");
        }
        String normalizedOrigin = require(origin, "origin", 32).toUpperCase(Locale.ROOT);
        if (!ORIGINS.contains(normalizedOrigin)) {
            throw ApiException.badRequest("origin must be FALSE_POSITIVE or MANUAL");
        }
        String normalizedReason = require(reason, "reason", 4096);
        Duration window = window(windowSeconds);
        lock.acquire();
        Instant now = Instant.now();
        repository.deleteByTenantIdAndExpiresAtLessThanEqual(tenant, now);
        repository.flush();
        var existing = repository.findByTenantIdAndRuleIdAndEntityKey(tenant, normalizedRule,
                normalizedEntity == null ? ANY_ENTITY : normalizedEntity);
        if (existing.isEmpty() && repository.countByTenantId(tenant) >= MAX_ACTIVE_WINDOWS) {
            throw ApiException.conflict("active suppression windows exceed the per-tenant limit of "
                    + MAX_ACTIVE_WINDOWS);
        }
        AlarmSuppressionEntity stored = existing.orElseGet(AlarmSuppressionEntity::new);
        stored.setTenantId(tenant);
        stored.setRuleId(normalizedRule);
        stored.setEntityKey(normalizedEntity == null ? ANY_ENTITY : normalizedEntity);
        stored.setOrigin(normalizedOrigin);
        stored.setReason(normalizedReason);
        stored.setAlarmId(optional(alarmId, 255));
        stored.setActor(optional(actor, 128));
        Instant requestedExpiry = now.plus(window);
        // Re-recording extends; it must not accidentally shorten an active window.
        stored.setExpiresAt(stored.getExpiresAt() != null && stored.getExpiresAt().isAfter(requestedExpiry)
                ? stored.getExpiresAt() : requestedExpiry);
        return toMap(repository.save(stored));
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list() {
        Instant now = Instant.now();
        return repository.findByTenantIdOrderByExpiresAtDesc(TenantContext.require()).stream()
                .filter(window -> window.getExpiresAt() != null && window.getExpiresAt().isAfter(now))
                .map(AlarmSuppressionService::toMap)
                .toList();
    }

    @Transactional
    public void release(String ruleId, String entity) {
        String tenant = TenantContext.require();
        String normalizedRule = require(ruleId, "ruleId", 255);
        String normalizedEntity = optional(entity, 255);
        lock.acquire();
        repository.deleteByTenantIdAndRuleIdAndEntityKey(tenant, normalizedRule,
                normalizedEntity == null ? ANY_ENTITY : normalizedEntity);
    }

    /** Exact entity first, plus the rule-wide scope, so one lookup answers both. */
    private static List<String> scopes(String entity) {
        String normalized = entity == null || entity.isBlank() ? ANY_ENTITY : entity.trim();
        return ANY_ENTITY.equals(normalized) ? List.of(ANY_ENTITY) : List.of(normalized, ANY_ENTITY);
    }

    private static Duration window(Long windowSeconds) {
        if (windowSeconds == null) {
            return DEFAULT_WINDOW;
        }
        if (windowSeconds <= 0) {
            throw ApiException.badRequest("windowSeconds must be positive");
        }
        Duration requested = Duration.ofSeconds(windowSeconds);
        if (requested.compareTo(MAX_WINDOW) > 0) {
            throw ApiException.badRequest("windowSeconds must not exceed " + MAX_WINDOW.toSeconds());
        }
        return requested;
    }

    private static Map<String, Object> toMap(AlarmSuppressionEntity entity) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", entity.getId());
        result.put("ruleId", entity.getRuleId());
        result.put("entity", entity.getEntityKey().isEmpty() ? null : entity.getEntityKey());
        result.put("origin", entity.getOrigin());
        result.put("reason", entity.getReason());
        result.put("alarmId", entity.getAlarmId());
        result.put("actor", entity.getActor());
        result.put("expiresAt", entity.getExpiresAt());
        return result;
    }

    private static String require(String value, String name, int maxLength) {
        String normalized = optional(value, maxLength);
        if (normalized == null) {
            throw ApiException.badRequest(name + " is required");
        }
        return normalized;
    }

    private static String optional(String value, int maxLength) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.length() > maxLength) {
            throw ApiException.badRequest("value is longer than " + maxLength + " characters");
        }
        return normalized;
    }
}
