package com.socp.search.config.domain;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * 检索用归一化事件（等价 com.siem 归一化事件的最小集）。
 * eventId 为幂等键：同一事件重发时（Kafka 至少一次语义）消费端据此去重。
 *
 * <p>字段分层（2026-08-11）：
 * <ul>
 *   <li>{@code fields}：兼容键（src_ip / user / host / msg ...），Detection 规则与存量逻辑依赖；
 *       顶层 source/host/severity 与 fields 里同名 text 字段共存（OS mapping 已固化）。</li>
 *   <li>{@code ecs}：Canonical Event Schema 字段（event.code / source.ip / process.name ...），
 *       独立命名空间避免与 fields 的 text 键冲突；OS 里落在 {@code ecs.*}。</li>
 * </ul>
 */
public record SearchEvent(
        String eventId,
        Instant timestamp,
        String source,
        String host,
        String severity,
        String msg,
        Map<String, String> fields,
        Map<String, String> ecs
) {
    /** Version carried on every Kafka/OpenSearch envelope. */
    @JsonProperty("schemaVersion")
    public String schemaVersion() {
        return com.socp.search.config.schema.CanonicalEventSchema.CURRENT;
    }

    /** Tenant is duplicated at the envelope boundary for consumers that do not
     * understand the legacy fields map. */
    @JsonProperty("tenantId")
    public String tenantId() {
        return fields == null ? null : fields.get("tenant_id");
    }

    public SearchEvent(Instant timestamp, String source, String host, String severity, String msg, Map<String, String> fields) {
        this(UUID.randomUUID().toString(), timestamp, source, host, severity, msg, fields, Map.of());
    }

    public SearchEvent(Instant timestamp, String source, String host, String severity, String msg,
                       Map<String, String> fields, Map<String, String> ecs) {
        this(UUID.randomUUID().toString(), timestamp, source, host, severity, msg, fields, ecs);
    }

    public String get(String key) {
        return switch (key) {
            case "eventId" -> eventId;
            case "timestamp" -> timestamp.toString();
            case "source" -> source;
            case "host" -> host;
            case "severity" -> severity;
            case "msg" -> msg;
            default -> {
                String v = fields.get(key);
                yield v != null ? v : ecs.get(key);
            }
        };
    }
}
