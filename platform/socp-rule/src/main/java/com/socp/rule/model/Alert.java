package com.socp.rule.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
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
        List<SecurityEvent> evidence,
        String title
) {
    public Alert {
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
        title = title == null || title.isBlank() ? ruleName : title;
    }

    /** Backwards-compatible constructor for callers using the original alert shape. */
    public Alert(String id, Instant timestamp, String ruleId, String ruleName,
                 Severity severity, String message, String entity,
                 List<SecurityEvent> evidence) {
        this(id, timestamp, ruleId, ruleName, severity, message, entity, evidence, ruleName);
    }

    public Alert(String ruleId, String ruleName, Severity severity,
                 String message, String entity, List<SecurityEvent> evidence) {
        this(stableId(ruleId, entity, evidence), alertTimestamp(evidence), ruleId, ruleName,
                severity, message, entity, evidence, ruleName);
    }

    public Alert(String ruleId, String ruleName, Severity severity,
                 String title, String message, String entity,
                 List<SecurityEvent> evidence) {
        this(stableId(ruleId, entity, evidence), alertTimestamp(evidence), ruleId, ruleName,
                severity, message, entity, evidence, title);
    }

    /**
     * Builds an alert whose identity treats evidence as a set rather than an
     * arrival-ordered sequence. Threshold/window rules use this form because
     * a replay after Kafka rebalance may rebuild the same window in a different
     * order while still representing the same logical alert.
     */
    public static Alert withUnorderedEvidence(String ruleId, String ruleName,
                                              Severity severity, String message,
                                              String entity, List<SecurityEvent> evidence) {
        return withUnorderedEvidence(ruleId, ruleName, severity, ruleName, message, entity, evidence);
    }

    public static Alert withUnorderedEvidence(String ruleId, String ruleName,
                                              Severity severity, String title,
                                              String message, String entity,
                                              List<SecurityEvent> evidence) {
        List<SecurityEvent> safeEvidence = evidence == null ? List.of() : List.copyOf(evidence);
        return new Alert(stableId(ruleId, entity, safeEvidence, true),
                alertTimestamp(safeEvidence), ruleId, ruleName, severity, message,
                entity, safeEvidence, title);
    }

    /**
     * Stable across a replay of the same evidence window. This lets alert-web
     * use the source alert id as its idempotency key when a consumer crashes
     * after detection but before the alert transaction completes.
     */
    private static String stableId(String ruleId, String entity, List<SecurityEvent> evidence) {
        return stableId(ruleId, entity, evidence, false);
    }

    private static String stableId(String ruleId, String entity,
                                   List<SecurityEvent> evidence, boolean unorderedEvidence) {
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
            List<String> eventIds = new ArrayList<>();
            for (SecurityEvent event : evidence) {
                if (event != null) eventIds.add(event.id());
            }
            if (unorderedEvidence) {
                eventIds.sort(Comparator.nullsFirst(Comparator.naturalOrder()));
            }
            for (String eventId : eventIds) {
                fingerprint.append('|').append(eventId);
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
