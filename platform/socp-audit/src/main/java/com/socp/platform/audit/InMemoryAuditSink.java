package com.socp.platform.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.stream.Collectors;

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

    /** 最近 N 条审计记录（新的在前），支持按 action 过滤。 */
    public List<AuditRecord> recent(int limit, String action) {
        java.util.ArrayList<AuditRecord> all = new java.util.ArrayList<>(buffer);
        java.util.Collections.reverse(all); // 新→旧
        java.util.stream.Stream<AuditRecord> s = all.stream();
        if (action != null && !action.isBlank()) {
            s = s.filter(r -> r.action().contains(action));
        }
        return s.limit(limit).collect(Collectors.toList());
    }
}
