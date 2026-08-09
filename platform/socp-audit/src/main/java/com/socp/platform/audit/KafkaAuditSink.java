package com.socp.platform.audit;

import com.socp.platform.tenant.TenantContext;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * Kafka 审计出口：把 AuditRecord 发到 socp-audit topic（多租户前缀由 TenantContext.prefix 处理）。
 * 仅当 classpath 有 KafkaTemplate 且 socp.audit.sink=kafka 时由 AuditAutoConfiguration 装配。
 * 对应 architecture.md §3 / P1：@AuditOperation → Kafka socp-audit → soc-base 落 PostgreSQL。
 */
public class KafkaAuditSink implements AuditSink {
    private final KafkaTemplate<String, AuditRecord> template;
    private final String topic;

    public KafkaAuditSink(KafkaTemplate<String, AuditRecord> template, String topic) {
        this.template = template;
        this.topic = topic;
    }

    @Override
    public void publish(AuditRecord record) {
        template.send(TenantContext.prefix(topic), record.tenantId(), record);
    }
}
