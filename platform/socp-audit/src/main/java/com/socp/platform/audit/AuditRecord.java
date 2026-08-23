package com.socp.platform.audit;

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
        String tenant = com.socp.platform.tenant.TenantContext.get();
        if (tenant == null || tenant.isBlank()) tenant = "default";
        return new AuditRecord(
                java.util.UUID.randomUUID().toString(),
                tenant,
                action, currentOperator(), target, result, Instant.now());
    }

    private static String currentOperator() {
        var attributes = org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();
        if (attributes instanceof org.springframework.web.context.request.ServletRequestAttributes servlet) {
            String user = servlet.getRequest().getHeader("X-Socp-User");
            if (user != null && !user.isBlank()) return user;
            String service = servlet.getRequest().getHeader(
                    com.socp.platform.tenant.ServiceRequestSignature.SERVICE_HEADER);
            if (service != null && !service.isBlank()) return "service:" + service;
        }
        return "system";
    }
}
