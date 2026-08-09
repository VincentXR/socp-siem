package com.socp.platform.audit;

import java.time.Instant;

/** 审计记录：操作级留痕，落到 Kafka socp-audit → soc-base → PostgreSQL（见 §3 / P1 / P2） */
public record AuditRecord(
        String tenantId,
        String action,
        String operator,
        String target,
        String result,
        Instant timestamp
) {
    public static AuditRecord of(String action, String target, String result) {
        return new AuditRecord(
                com.socp.platform.tenant.TenantContext.get(),
                action, "system", target, result, Instant.now());
    }
}
