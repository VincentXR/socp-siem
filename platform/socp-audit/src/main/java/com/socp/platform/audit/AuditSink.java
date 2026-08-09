package com.socp.platform.audit;

import java.util.List;

/** 审计落地出口。本地切片用内存实现；Docker 环境切到 KafkaAuditSink 发布到 socp-audit topic。 */
public interface AuditSink {
    void publish(AuditRecord record);

    /** 最近 N 条审计记录（默认实现返回空；InMemoryAuditSink 提供真实查询）。 */
    default List<AuditRecord> recent(int limit, String action) {
        return List.of();
    }

    /** 已留痕的记录数（默认 0；InMemoryAuditSink 返回实际计数）。 */
    default int size() {
        return 0;
    }
}
