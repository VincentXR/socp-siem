package com.socp.platform.audit;

/** 审计落地出口。本地切片用内存实现；Docker 环境切到 KafkaAuditSink 发布到 socp-audit topic。 */
public interface AuditSink {
    void publish(AuditRecord record);
}
