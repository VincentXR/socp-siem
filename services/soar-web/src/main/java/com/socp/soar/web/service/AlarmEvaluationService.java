package com.socp.soar.web.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.socp.platform.tenant.context.TenantContext;
import com.socp.platform.audit.api.AuditOperation;
import com.socp.soar.web.persistence.entity.AlarmEvaluationEntity;
import com.socp.soar.web.persistence.repository.AlarmEvaluationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Provides tenant-scoped idempotency for alarm-triggered SOAR evaluations. */
@Service
public class AlarmEvaluationService {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() { };
    private static final Duration STALE_PROCESSING = Duration.ofMinutes(2);

    private final PlaybookExecutor executor;
    private final AlarmEvaluationRepository repository;
    private final SoarV2AutomationRuleService automationRules;
    private final com.socp.soar.web.config.SoarRuntimeProperties properties;

    /** Legacy constructor used by unit tests: keeps the V1-only evaluation behaviour. */
    public AlarmEvaluationService(PlaybookExecutor executor, AlarmEvaluationRepository repository) {
        this(executor, repository, null, legacyOnlyProperties());
    }

    @org.springframework.beans.factory.annotation.Autowired
    public AlarmEvaluationService(PlaybookExecutor executor, AlarmEvaluationRepository repository,
                                  SoarV2AutomationRuleService automationRules,
                                  com.socp.soar.web.config.SoarRuntimeProperties properties) {
        this.executor = executor;
        this.repository = repository;
        this.automationRules = automationRules;
        this.properties = properties;
    }

    private static com.socp.soar.web.config.SoarRuntimeProperties legacyOnlyProperties() {
        com.socp.soar.web.config.SoarRuntimeProperties legacy = new com.socp.soar.web.config.SoarRuntimeProperties();
        legacy.setV2EvaluationEnabled(false);
        return legacy;
    }

    @Transactional
    @AuditOperation(action = "SOAR_EVALUATE_ALERT", target = "t_alarm_evaluation")
    public Map<String, Object> evaluate(Map<String, Object> alarm) {
        String tenant = tenant();
        String alarmId = text(alarm.get("id"));
        if (alarmId == null) throw new IllegalArgumentException("alarm id is required");
        String id = evaluationId(tenant, alarmId);
        Instant now = Instant.now();
        AlarmEvaluationEntity receipt = repository.findByIdAndTenantIdForUpdate(id, tenant)
                // The compatibility constructor is used by lightweight unit
                // tests whose mock repository only implements the historical
                // finder.  The fallback is also the correct path for a new
                // receipt because a row cannot be locked before it exists.
                .orElseGet(() -> repository.findByIdAndTenantId(id, tenant).orElse(null));
        if (receipt != null && "COMPLETED".equals(receipt.getStatus())) {
            return cached(receipt);
        }
        if (receipt != null && "PROCESSING".equals(receipt.getStatus())
                && receipt.getUpdatedAt() != null
                && receipt.getUpdatedAt().isAfter(now.minus(STALE_PROCESSING))) {
            throw new EvaluationInProgressException(alarmId);
        }
        if (receipt == null) {
            receipt = new AlarmEvaluationEntity();
            receipt.setId(id);
            receipt.setTenantId(tenant);
            receipt.setAlarmId(alarmId);
            receipt.setCreatedAt(now);
        }
        receipt.setStatus("PROCESSING");
        receipt.setLastError(null);
        receipt.setUpdatedAt(now);
        repository.saveAndFlush(receipt);

        try {
            Map<String, Object> result = new LinkedHashMap<>();
            // Single-path evaluation (design §8.1): V2 automation rules and the
            // legacy executor must never both run for the same alarm, otherwise
            // one trigger fans out into duplicate response actions.
            if (properties != null && properties.isV2EvaluationEnabled() && automationRules != null) {
                Map<String, Object> envelope = new LinkedHashMap<>(alarm);
                envelope.putIfAbsent("schemaVersion", "soar.event/v1");
                envelope.putIfAbsent("eventId", "alert:" + alarmId + ":created:1");
                envelope.putIfAbsent("eventType", "alert.created");
                envelope.putIfAbsent("tenantId", tenant);
                envelope.putIfAbsent("producer", "alert-web");
                envelope.putIfAbsent("occurredAt", text(alarm.get("occurredAt")) == null
                        ? Instant.now().toString() : text(alarm.get("occurredAt")));
                envelope.putIfAbsent("subject", Map.of("type", "alert", "id", alarmId));
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("alert", new LinkedHashMap<>(alarm));
                if (alarm.get("entities") instanceof java.util.List<?> entities) data.put("entities", entities);
                if (alarm.get("evidence") instanceof java.util.List<?> evidence) data.put("evidence", evidence);
                envelope.putIfAbsent("data", data);
                Map<String, Object> trace = new LinkedHashMap<>();
                trace.put("correlationId", alarmId);
                trace.put("causationId", text(alarm.get("triggerEventId")) == null
                        ? alarmId : text(alarm.get("triggerEventId")));
                trace.put("automationDepth", 0);
                envelope.putIfAbsent("trace", trace);
                result.put("automation", automationRules.evaluate(envelope));
            } else {
                result.putAll(executor.evaluate(alarm));
            }
            receipt.setResultJson(MAPPER.writeValueAsString(result));
            receipt.setStatus("COMPLETED");
            receipt.setUpdatedAt(Instant.now());
            repository.save(receipt);
            return result;
        } catch (RuntimeException failure) {
            receipt.setStatus("FAILED");
            receipt.setLastError(safeFailure(failure.getMessage(), 1024));
            receipt.setUpdatedAt(Instant.now());
            repository.save(receipt);
            throw failure;
        } catch (Exception serializationFailure) {
            receipt.setStatus("FAILED");
            receipt.setLastError(safeFailure(serializationFailure.getMessage(), 1024));
            receipt.setUpdatedAt(Instant.now());
            repository.save(receipt);
            throw new IllegalStateException("SOAR evaluation receipt serialization failed", serializationFailure);
        }
    }

    private static Map<String, Object> cached(AlarmEvaluationEntity receipt) {
        try {
            Map<String, Object> result = new LinkedHashMap<>(MAPPER.readValue(receipt.getResultJson(), MAP_TYPE));
            result.put("duplicate", true);
            return result;
        } catch (Exception failure) {
            throw new IllegalStateException("invalid SOAR evaluation receipt", failure);
        }
    }

    private static String evaluationId(String tenant, String alarmId) {
        return UUID.nameUUIDFromBytes((tenant + "\u0000" + alarmId).getBytes(StandardCharsets.UTF_8)).toString();
    }

    private static String tenant() {
        return TenantContext.require();
    }

    private static String text(Object value) {
        if (value == null) return null;
        String text = String.valueOf(value);
        return text.isBlank() ? null : text;
    }

    private static String truncate(String value, int length) {
        if (value == null) return "unknown failure";
        return value.length() <= length ? value : value.substring(0, length);
    }

    private static String safeFailure(String value, int length) {
        return truncate(value, length)
                .replaceAll("(?i)(bearer\\s+)[^\\s,;]+", "$1[REDACTED]")
                .replaceAll("(?i)((?:secret|token|password|authorization|api[_-]?key|cookie)\\s*[:=]\\s*)[^\\s,;]+",
                        "$1[REDACTED]");
    }

    public static class EvaluationInProgressException extends RuntimeException {
        public EvaluationInProgressException(String alarmId) {
            super("SOAR evaluation is already in progress for alarm " + alarmId);
        }
    }
}
