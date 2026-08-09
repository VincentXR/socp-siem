package com.socp.rule.model;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * 归一化后的安全事件模型——所有日志源最终都被解析成这个统一的形态。
 *
 * <p>由 com.siem 的 {@code com.siem.model.SecurityEvent} 迁移（SOCP 迁移设计 §2）。
 * 使用 Java 21 record 表达不可变数据载体。
 */
public record SecurityEvent(
        String id,
        Instant timestamp,
        String source,                 // 来源类别：syslog / firewall / auth / web ...
        String host,                   // 产生事件的主机
        String raw,                    // 原始日志行
        Map<String, String> fields,    // 结构化字段
        Severity severity              // 解析出的严重级别（缺省 INFO）
) {
    public SecurityEvent(Instant timestamp, String source, String host,
                         String raw, Map<String, String> fields, Severity severity) {
        this(UUID.randomUUID().toString(), timestamp, source, host, raw, fields, severity);
    }

    /** 便捷取字段，缺失返回 null */
    public String get(String key) {
        return fields.get(key);
    }
}
