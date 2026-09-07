package com.socp.platform.audit.model;
import com.socp.platform.tenant.context.AuthenticatedIdentityContext;
import java.time.Instant;

/** 审计记录：操作级留痕，落到 Kafka socp-audit → soc-base → PostgreSQL（见 §3 / P1 / P2） */
public record AuditRecord(
        String eventId,
        String tenantId,
        String action,
        String operator,
        String target,
        String result,
        Instant timestamp
) {
    public AuditRecord(String tenantId, String action, String operator, String target,
                       String result, Instant timestamp) {
        this(java.util.UUID.randomUUID().toString(), tenantId, action, operator, target, result, timestamp);
    }

    public static AuditRecord of(String action, String target, String result) {
        String tenant = com.socp.platform.tenant.context.TenantContext.require();
        return new AuditRecord(
                java.util.UUID.randomUUID().toString(),
                tenant,
                action, currentOperator(), target, result, Instant.now());
    }

    private static String currentOperator() {
        return AuthenticatedIdentityContext.current().map(identity -> identity.subject()).orElse("system");
    }
}
