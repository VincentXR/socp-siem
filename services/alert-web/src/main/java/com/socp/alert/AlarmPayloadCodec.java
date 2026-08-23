package com.socp.alert;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class AlarmPayloadCodec {

    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private AlarmPayloadCodec() {
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> read(String payload) throws JsonProcessingException {
        return MAPPER.readValue(payload, Map.class);
    }

    static String write(Alarm alarm, List<AlarmEvidenceInput> evidence) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", alarm.getId());
        payload.put("tenantId", alarm.getTenantId());
        payload.put("ruleId", alarm.getRuleId());
        payload.put("ruleName", alarm.getRuleName());
        payload.put("severity", alarm.getSeverity() == null ? null : alarm.getSeverity().name());
        payload.put("message", alarm.getMessage());
        payload.put("entity", alarm.getEntity());
        payload.put("mitre", alarm.getMitre());
        payload.put("riskScore", alarm.getRiskScore());
        payload.put("riskLevel", alarm.getRiskLevel());
        payload.put("occurredAt", alarm.getOccurredAt());
        payload.put("triggerIngestedAt", alarm.getTriggerIngestedAt());
        payload.put("alertCreatedAt", alarm.getAlertCreatedAt());
        payload.put("processingLatencyMs", alarm.getProcessingLatencyMs());
        payload.put("triggerEventId", alarm.getTriggerEventId());
        payload.put("evidence", evidence == null ? List.of() : evidence);
        try {
            return MAPPER.writeValueAsString(payload);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("cannot serialize alarm delivery payload", failure);
        }
    }

    static Alarm toAlarm(Map<String, Object> values) {
        Alarm alarm = new Alarm();
        alarm.setId(text(values.get("id")));
        alarm.setTenantId(text(values.getOrDefault("tenantId", values.getOrDefault("tenant_id", "default"))));
        alarm.setRuleId(text(values.get("ruleId")));
        alarm.setRuleName(text(values.get("ruleName")));
        try {
            alarm.setSeverity(values.get("severity") == null ? null
                    : Severity.valueOf(String.valueOf(values.get("severity")).toUpperCase()));
        } catch (IllegalArgumentException ignored) {
            alarm.setSeverity(null);
        }
        alarm.setMessage(text(values.get("message")));
        alarm.setEntity(text(values.get("entity")));
        alarm.setMitre(text(values.get("mitre")));
        alarm.setRiskScore(values.get("riskScore") instanceof Number number ? number.intValue() : null);
        alarm.setRiskLevel(text(values.get("riskLevel")));
        alarm.setOccurredAt(instant(values.get("occurredAt")));
        alarm.setTriggerIngestedAt(instant(values.get("triggerIngestedAt")));
        alarm.setAlertCreatedAt(instant(values.get("alertCreatedAt")));
        if (values.get("processingLatencyMs") instanceof Number number) {
            alarm.setProcessingLatencyMs(number.longValue());
        }
        alarm.setTriggerEventId(text(values.get("triggerEventId")));
        return alarm;
    }

    private static Instant instant(Object value) {
        if (value == null) return null;
        try {
            return Instant.parse(String.valueOf(value));
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static String text(Object value) {
        if (value == null) return null;
        String text = String.valueOf(value);
        return text.isBlank() ? null : text;
    }
}
