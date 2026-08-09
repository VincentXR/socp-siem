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
        this(UUID.randomUUID().toString(), Instant.now(), ruleId, ruleName,
                severity, message, entity, evidence);
    }

    @Override
    public String toString() {
        return "[%s] %s | %s | 实体=%s | 证据数=%d | %s"
                .formatted(severity, ruleName, id, entity, evidence.size(), message);
    }
}
