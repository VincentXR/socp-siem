package com.socp.alert.service;

import com.socp.alert.persistence.entity.AlarmFeedbackEntity;
import com.socp.alert.repository.AlarmFeedbackRepository;
import com.socp.platform.error.exception.ApiException;
import com.socp.platform.tenant.context.TenantContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Durable feedback service; the unique tenant/alarm/kind row makes retries safe. */
@Service
public class AlarmFeedbackService {

    private static final List<String> KINDS = List.of("FALSE_POSITIVE", "RULE_EXCEPTION");
    private final AlarmFeedbackRepository repository;

    public AlarmFeedbackService(AlarmFeedbackRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public Map<String, Object> save(String alarmId, String kind, String reason,
                                    Instant expiresAt, String actor) {
        String tenant = TenantContext.require();
        String normalizedAlarmId = normalizeRequired(alarmId, "alarmId", 255);
        String normalizedKind = normalizeRequired(kind, "kind", 32).toUpperCase(Locale.ROOT);
        if (!KINDS.contains(normalizedKind)) {
            throw ApiException.badRequest("kind must be FALSE_POSITIVE or RULE_EXCEPTION");
        }
        String normalizedReason = normalizeRequired(reason, "reason", 4096);
        if (expiresAt != null && !expiresAt.isAfter(Instant.now())) {
            throw ApiException.badRequest("expiresAt must be in the future");
        }
        AlarmFeedbackEntity entity = repository.findByTenantIdAndAlarmIdAndKind(
                        tenant, normalizedAlarmId, normalizedKind)
                .orElseGet(AlarmFeedbackEntity::new);
        entity.setTenantId(tenant);
        entity.setAlarmId(normalizedAlarmId);
        entity.setKind(normalizedKind);
        entity.setReason(normalizedReason);
        entity.setExpiresAt(expiresAt);
        entity.setActor(normalizeOptional(actor, 128));
        AlarmFeedbackEntity saved = repository.save(entity);
        return toMap(saved);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(String alarmId) {
        String tenant = TenantContext.require();
        String normalizedAlarmId = normalizeRequired(alarmId, "alarmId", 255);
        return repository.findByTenantIdAndAlarmIdOrderByCreatedAtDesc(tenant, normalizedAlarmId)
                .stream().map(AlarmFeedbackService::toMap).toList();
    }

    private static Map<String, Object> toMap(AlarmFeedbackEntity entity) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", entity.getId());
        result.put("tenantId", entity.getTenantId());
        result.put("alarmId", entity.getAlarmId());
        result.put("kind", entity.getKind());
        result.put("reason", entity.getReason());
        result.put("expiresAt", entity.getExpiresAt());
        result.put("actor", entity.getActor());
        result.put("createdAt", entity.getCreatedAt());
        return result;
    }

    private static String normalizeRequired(String value, String name, int maxLength) {
        String normalized = normalizeOptional(value, maxLength);
        if (normalized == null) throw ApiException.badRequest(name + " is required");
        return normalized;
    }

    private static String normalizeOptional(String value, int maxLength) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.trim();
        if (normalized.length() > maxLength) {
            throw ApiException.badRequest("value is longer than " + maxLength + " characters");
        }
        return normalized;
    }
}
