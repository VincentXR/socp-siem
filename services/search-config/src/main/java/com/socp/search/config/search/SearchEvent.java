package com.socp.search.config.search;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 检索用归一化事件（等价 com.siem 归一化事件的最小集）。
 */
public record SearchEvent(
        Instant timestamp,
        String source,
        String host,
        String severity,
        String msg,
        Map<String, String> fields
) {
    public String get(String key) {
        return switch (key) {
            case "timestamp" -> timestamp.toString();
            case "source" -> source;
            case "host" -> host;
            case "severity" -> severity;
            case "msg" -> msg;
            default -> fields.get(key);
        };
    }
}
