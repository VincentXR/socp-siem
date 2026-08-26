package com.socp.soar.web.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.socp.platform.tenant.context.TenantContext;
import com.socp.soar.web.persistence.entity.AlarmEvaluationEntity;
import com.socp.soar.web.persistence.repository.AlarmEvaluationRepository;
import org.springframework.stereotype.Service;

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

    public AlarmEvaluationService(PlaybookExecutor executor, AlarmEvaluationRepository repository) {
        this.executor = executor;
        this.repository = repository;
    }

    public Map<String, Object> evaluate(Map<String, Object> alarm) {
        String tenant = tenant();
        String alarmId = text(alarm.get("id"));
        if (alarmId == null) throw new IllegalArgumentException("alarm id is required");
        String id = evaluationId(tenant, alarmId);
        Instant now = Instant.now();
        AlarmEvaluationEntity receipt = repository.findByIdAndTenantId(id, tenant).orElse(null);
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
            Map<String, Object> result = executor.evaluate(alarm);
            receipt.setResultJson(MAPPER.writeValueAsString(result));
            receipt.setStatus("COMPLETED");
            receipt.setUpdatedAt(Instant.now());
            repository.save(receipt);
            return result;
        } catch (RuntimeException failure) {
            receipt.setStatus("FAILED");
            receipt.setLastError(truncate(failure.getMessage(), 1024));
            receipt.setUpdatedAt(Instant.now());
            repository.save(receipt);
            throw failure;
        } catch (Exception serializationFailure) {
            receipt.setStatus("FAILED");
            receipt.setLastError(truncate(serializationFailure.getMessage(), 1024));
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

    public static class EvaluationInProgressException extends RuntimeException {
        public EvaluationInProgressException(String alarmId) {
            super("SOAR evaluation is already in progress for alarm " + alarmId);
        }
    }
}
