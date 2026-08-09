package com.socp.platform.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentLinkedQueue;

/** 默认审计出口：内存队列 + 日志。生产环境替换为 KafkaAuditSink（见 KafkaAuditSink 注释）。 */
public class InMemoryAuditSink implements AuditSink {
    private static final Logger log = LoggerFactory.getLogger(InMemoryAuditSink.class);
    private final ConcurrentLinkedQueue<AuditRecord> buffer = new ConcurrentLinkedQueue<>();

    @Override
    public void publish(AuditRecord record) {
        buffer.add(record);
        log.info("[审计] tenant={} action={} target={} result={}", record.tenantId(),
                record.action(), record.target(), record.result());
    }

    public int size() {
        return buffer.size();
    }
}
