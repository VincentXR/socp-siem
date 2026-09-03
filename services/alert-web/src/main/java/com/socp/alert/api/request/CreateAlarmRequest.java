package com.socp.alert.api.request;

import com.socp.alert.domain.AlarmEvidenceInput;
import com.socp.alert.domain.Severity;
import jakarta.validation.constraints.NotBlank;

import java.time.Instant;
import java.util.List;

public record CreateAlarmRequest(
        @NotBlank String ruleId,
        @NotBlank String ruleName,
        Severity severity,
        String message,
        String entity,
        String mitre,
        Instant occurredAt,
        Integer riskScore,
        List<AlarmEvidenceInput> evidence,
        String sourceAlertId,
        Instant triggerIngestedAt,
        Instant alertCreatedAt,
        Long processingLatencyMs,
        String triggerEventId,
        Instant detectionOutboxClaimedAt,
        String title) {

    /** Backwards-compatible constructor for callers using the original request shape. */
    public CreateAlarmRequest(String ruleId, String ruleName, Severity severity, String message,
                              String entity, String mitre, Instant occurredAt, Integer riskScore,
                              List<AlarmEvidenceInput> evidence, String sourceAlertId,
                              Instant triggerIngestedAt, Instant alertCreatedAt, Long processingLatencyMs,
                              String triggerEventId, Instant detectionOutboxClaimedAt) {
        this(ruleId, ruleName, severity, message, entity, mitre, occurredAt, riskScore, evidence,
                sourceAlertId, triggerIngestedAt, alertCreatedAt, processingLatencyMs,
                triggerEventId, detectionOutboxClaimedAt, null);
    }
}
