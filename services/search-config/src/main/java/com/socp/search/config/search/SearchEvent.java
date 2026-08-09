package com.socp.search.config.search;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 检索用归一化事件（等价 com.siem 归一化事件的最小集）。
 * eventId 为幂等键：同一事件重发时（Kafka 至少一次语义）消费端据此去重。
 */
public record SearchEvent(
        String eventId,
        Instant timestamp,
        String source,
        String host,
        String severity,
        String msg,
        Map<String, String> fields
) {
    public SearchEvent(Instant timestamp, String source, String host, String severity, String msg, Map<String, String> fields) {
        this(UUID.randomUUID().toString(), timestamp, source, host, severity, msg, fields);
    }

    public String get(String key) {
        return switch (key) {
            case "eventId" -> eventId;
            case "timestamp" -> timestamp.toString();
            case "source" -> source;
            case "host" -> host;
            case "severity" -> severity;
            case "msg" -> msg;
            default -> fields.get(key);
        };
    }
}
