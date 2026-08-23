package com.socp.rule.model;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 规则命中后产出的告警。evidence 记录触发该告警的原始事件链，便于溯源。
 * entity 为告警关联实体（如源 IP），用于去重/抑制与关联归并。由 com.siem 迁移。
 */
public record Alert(
        String id,
        Instant timestamp,
        String ruleId,
        String ruleName,
        Severity severity,
        String message,
        String entity,
        List<SecurityEvent> evidence
) {
    public Alert(String ruleId, String ruleName, Severity severity,
                 String message, String entity, List<SecurityEvent> evidence) {
        this(stableId(ruleId, entity, evidence), alertTimestamp(evidence), ruleId, ruleName,
                severity, message, entity, evidence == null ? List.of() : List.copyOf(evidence));
    }

    /**
     * Stable across a replay of the same evidence window. This lets alert-web
     * use the source alert id as its idempotency key when a consumer crashes
     * after detection but before the alert transaction completes.
     */
    private static String stableId(String ruleId, String entity, List<SecurityEvent> evidence) {
        String tenant = "default";
        if (evidence != null) {
            for (SecurityEvent event : evidence) {
                if (event != null) {
                    tenant = event.tenantId();
                    break;
                }
            }
        }
        StringBuilder fingerprint = new StringBuilder(tenant)
                .append('|').append(String.valueOf(ruleId))
                .append('|').append(String.valueOf(entity));
        if (evidence != null) {
            for (SecurityEvent event : evidence) {
                if (event != null) fingerprint.append('|').append(event.id());
            }
        }
        return UUID.nameUUIDFromBytes(fingerprint.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
    }

    private static Instant alertTimestamp(List<SecurityEvent> evidence) {
        if (evidence != null) {
            for (int i = evidence.size() - 1; i >= 0; i--) {
                if (evidence.get(i) != null && evidence.get(i).timestamp() != null) {
                    return evidence.get(i).timestamp();
                }
            }
        }
        return Instant.now();
    }

    @Override
    public String toString() {
        return "[%s] %s | %s | 实体=%s | 证据数=%d | %s"
                .formatted(severity, ruleName, id, entity, evidence.size(), message);
    }
}
