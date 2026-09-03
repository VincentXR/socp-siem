package com.socp.alert.service;

import com.socp.alert.domain.Alarm;
import com.socp.alert.domain.AlarmEvidenceInput;
import com.socp.alert.domain.Severity;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AlarmPayloadCodecTest {

    @Test
    void writesAndReadsTheCompleteDurableAlarmPayload() throws Exception {
        Alarm alarm = new Alarm("R-1", "Brute Force", Severity.HIGH,
                "failed login from 203.0.113.10", "203.0.113.10", "T1110", "[{\"ioc\":\"203.0.113.10\"}]");
        alarm.setId("alarm-1");
        alarm.setTitle("Admin login anomaly");
        alarm.setTenantId("tenant-a");
        alarm.setRiskScore(82);
        alarm.setRiskLevel("HIGH");
        alarm.setOccurredAt(Instant.parse("2026-08-30T10:00:00Z"));
        alarm.setTriggerIngestedAt(Instant.parse("2026-08-30T09:59:58Z"));
        alarm.setAlertCreatedAt(Instant.parse("2026-08-30T09:59:59Z"));
        alarm.setProcessingLatencyMs(1000L);
        alarm.setTriggerEventId("event-1");
        List<AlarmEvidenceInput> evidence = List.of(new AlarmEvidenceInput(
                "event-1", alarm.getOccurredAt(), "auth", "host-1", "HIGH", "raw",
                Map.of("user", "alice")));

        String payload = AlarmPayloadCodec.write(alarm, evidence);
        Map<String, Object> values = AlarmPayloadCodec.read(payload);
        Alarm decoded = AlarmPayloadCodec.toAlarm(values);

        assertThat(values).containsEntry("id", "alarm-1")
                .containsEntry("tenantId", "tenant-a")
                .containsKey("evidence");
        assertThat(decoded.getId()).isEqualTo("alarm-1");
        assertThat(decoded.getTenantId()).isEqualTo("tenant-a");
        assertThat(decoded.getSeverity()).isEqualTo(Severity.HIGH);
        assertThat(decoded.getTitle()).isEqualTo("Admin login anomaly");
        assertThat(decoded.getRiskScore()).isEqualTo(82);
        assertThat(decoded.getProcessingLatencyMs()).isEqualTo(1000L);
        assertThat(decoded.getOccurredAt()).isEqualTo(alarm.getOccurredAt());
        assertThat(AlarmPayloadCodec.write(alarm, null)).contains("\"evidence\":[]");
    }

    @Test
    void acceptsLegacyTenantFieldAndTreatsInvalidOptionalValuesAsAbsent() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("id", "legacy-1");
        values.put("tenant_id", "tenant-a");
        values.put("severity", "not-a-severity");
        values.put("message", " ");
        values.put("riskScore", 42);
        values.put("processingLatencyMs", 99);
        values.put("occurredAt", "invalid");
        values.put("triggerIngestedAt", null);
        values.put("alertCreatedAt", "invalid");

        Alarm alarm = AlarmPayloadCodec.toAlarm(values);

        assertThat(alarm.getTenantId()).isEqualTo("tenant-a");
        assertThat(alarm.getSeverity()).isNull();
        assertThat(alarm.getMessage()).isNull();
        assertThat(alarm.getOccurredAt()).isNull();
        assertThat(alarm.getAlertCreatedAt()).isNull();
        assertThat(alarm.getRiskScore()).isEqualTo(42);
        assertThat(alarm.getProcessingLatencyMs()).isEqualTo(99L);
    }

    @Test
    void rejectsMalformedPayloadAndInvalidTenantIdentity() {
        assertThatThrownBy(() -> AlarmPayloadCodec.read("not-json"))
                .isInstanceOf(com.fasterxml.jackson.core.JsonProcessingException.class);

        Map<String, Object> values = new LinkedHashMap<>();
        values.put("id", "alarm-2");
        values.put("tenantId", "tenant!");
        assertThatThrownBy(() -> AlarmPayloadCodec.toAlarm(values))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tenant");
    }
}
