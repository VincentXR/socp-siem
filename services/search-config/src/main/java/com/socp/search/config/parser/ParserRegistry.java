package com.socp.search.config.parser;

import com.socp.search.config.domain.ParseFormat;
import com.socp.search.config.config.SearchRuntimeRole;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 解析器注册表 + 源路由（2026-08-11）。
 *
 * <p><b>Source Router 优先</b>：按日志行/采集器标签（vendor / product / collector / source_type）
 * 直接选解析器，<b>不是</b>每条日志遍历全部规则。路由键命中顺序：
 * <ol>
 *   <li>显式 vendor/product（CEF / LEEF / Sysmon / syslog 特征前缀）；</li>
 *   <li>JSON 结构（Falco / Suricata / Sysmon / 采集器包装行）→ JsonParser，含 Sysmon 增强；</li>
 *   <li>syslog 头（&lt;PRI&gt;）；</li>
 *   <li>KV 形态（key=value）；</li>
 *   <li>其余原样收进 {@code event.message}。</li>
 * </ol>
 */
@Component
@SearchRuntimeRole(SearchRuntimeRole.Role.API)
public class ParserRegistry {

    private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();
    private static final int MAX_ENVELOPE_DEPTH = 4;
    public static final String FORMAT_MISMATCH = "format_mismatch";
    private static final List<String> IDENTITY_FIELDS = List.of(
            "eventId", "event.id", "event_id", "id", "topic", "partition", "offset",
            "source.topic", "source.partition", "source.offset", "kafka_topic",
            "kafka_partition", "kafka_offset", "file", "file_path", "source.file",
            "log.file.path", "batch_id", "batchId", "ingest_batch_id", "line_number",
            "lineNumber", "batch_line");

    private final List<EventParser> parsers;
    private final Map<String, EventParser> byVendor = new LinkedHashMap<>();

    public ParserRegistry() {
        parsers = List.of(
                new SysmonParser(),
                new FalcoParser(),
                new AuditdParser(),
                new JsonParser(),
                new SshdParser(),
                new SyslogParser(),
                new CefParser(),
                new LeefParser(),
                new KvParser());
        byVendor.put("sysmon", new SysmonParser());
        byVendor.put("falco", new FalcoParser());
        byVendor.put("auditd", new AuditdParser());
        byVendor.put("json", new JsonParser());
        byVendor.put("sshd", new SshdParser());
        byVendor.put("syslog", new SyslogParser());
        byVendor.put("cef", new CefParser());
        byVendor.put("leef", new LeefParser());
        byVendor.put("kv", new KvParser());
    }

    /**
     * 解析一行，返回 canonical 字段（ECS 风格键）。解析器按特征路由，不适用返回空 Map。
     *
     * @param vendorHint 采集器/任务声明的 vendor 提示（可为 null）；命中则只试该解析器
     */
    public Map<String, String> parse(String raw, String vendorHint) {
        return parseAuto(raw, vendorHint, 0);
    }

    private Map<String, String> parseAuto(String raw, String vendorHint, int depth) {
        if (raw == null || raw.isBlank()) {
            return Map.of();
        }
        if (vendorHint != null && !vendorHint.isBlank()) {
            EventParser p = byVendor.get(vendorHint.trim().toLowerCase());
            if (p != null) {
                Map<String, String> out = safeParse(p, raw);
                if (out != null) {
                    return finishAutoParse(p, raw, out, depth);
                }
            }
        }
        // 特征路由：Sysmon JSON（Event/EventData）→ JSON → syslog → CEF → LEEF → KV
        for (EventParser p : parsers) {
            Map<String, String> out = safeParse(p, raw);
            if (out != null) {
                return finishAutoParse(p, raw, out, depth);
            }
        }
        // 兜底：原文进 event.message
        return Map.of(CanonicalEvent.EVENT_MESSAGE, raw.trim());
    }

    private Map<String, String> finishAutoParse(EventParser parser, String raw,
                                               Map<String, String> parsed, int depth) {
        if (!"json".equals(parser.name())) return preserveIdentity(raw, parsed);
        // Generic JSON already preserves envelope identities. Only selective
        // vendor parsers need a separate metadata extraction pass.
        Map<String, String> nested = parseEmbeddedText(parsed, depth);
        if (nested == null) return parsed;
        Map<String, String> merged = new LinkedHashMap<>(parsed);
        merged.putAll(nested);
        return merged;
    }

    /**
     * Parses with a persisted source format. Unlike the legacy AUTO method, a
     * fixed format must not silently fall back to event.message. A mismatch
     * carries parse.error so ingestion can durably quarantine the original
     * line, while preview callers can still display the failure.
     *
     * <p>Vector transports source metadata in a JSON envelope. For a fixed
     * format the envelope is unwrapped first and the embedded message is sent
     * through the selected parser, while the envelope fields are retained.</p>
     */
    public Map<String, String> parse(String raw, ParseFormat format, String vendorHint) {
        if (raw == null || raw.isBlank()) {
            return Map.of();
        }
        ParseFormat selected = format == null ? ParseFormat.AUTO : format;
        if (selected == ParseFormat.AUTO) {
            return parse(raw, vendorHint);
        }

        EventParser parser = byVendor.get(selected.name().toLowerCase());
        if (parser == null) {
            return Map.of();
        }

        if (selected == ParseFormat.JSON) {
            Map<String, String> parsed = safeParse(parser, raw);
            return parsed == null ? formatMismatch(raw, "input does not match JSON format")
                    : mergeEmbeddedJson(parsed);
        }

        if (raw.stripLeading().startsWith("{")) {
            Map<String, String> envelope = safeParse(new JsonParser(), raw);
            if (envelope != null) {
                String message = envelope.get(CanonicalEvent.EVENT_MESSAGE);
                if (message == null || message.isBlank()) {
                    return envelope;
                }
                Map<String, String> nested = safeParse(parser, message);
                if (nested != null) {
                    Map<String, String> merged = new LinkedHashMap<>(envelope);
                    merged.putAll(nested);
                    return merged;
                }
                return formatMismatch(raw, "message does not match " + selected + " format");
            }
        }

        Map<String, String> direct = safeParse(parser, raw);
        return direct == null ? formatMismatch(raw, "input does not match " + selected + " format") : direct;
    }

    private Map<String, String> mergeEmbeddedJson(Map<String, String> envelope) {
        String message = envelope.get(CanonicalEvent.EVENT_MESSAGE);
        if (message == null || !message.stripLeading().startsWith("{")) return envelope;
        Map<String, String> nested = safeParse(new JsonParser(), message);
        if (nested == null || nested.isEmpty()) return envelope;
        Map<String, String> merged = new LinkedHashMap<>(envelope);
        merged.putAll(nested);
        return merged;
    }

    private Map<String, String> parseEmbeddedText(Map<String, String> envelope, int depth) {
        String message = envelope.get(CanonicalEvent.EVENT_MESSAGE);
        if (message == null || message.isBlank()) return null;
        if (message.stripLeading().startsWith("{")) {
            if (depth >= MAX_ENVELOPE_DEPTH) {
                return parseFailure(message, "JSON envelope nesting exceeds " + MAX_ENVELOPE_DEPTH);
            }
            return parseAuto(message, null, depth + 1);
        }
        Map<String, String> authentication = safeParse(new SshdParser(), message);
        return authentication != null ? authentication : safeParse(new SyslogParser(), message);
    }

    /** Vendor field extraction must not discard the producer's replay identity. */
    private Map<String, String> preserveIdentity(String raw, Map<String, String> parsed) {
        if (!raw.stripLeading().startsWith("{")) return parsed;
        try {
            var original = MAPPER.readTree(raw);
            Map<String, String> result = new LinkedHashMap<>(parsed);
            for (String field : IDENTITY_FIELDS) {
                var value = original.get(field);
                if (value == null && field.contains(".")) {
                    value = original;
                    for (String segment : field.split("\\.")) {
                        value = value.get(segment);
                        if (value == null) break;
                    }
                }
                if (value != null && value.isValueNode() && !value.isNull() && !value.asText().isBlank()) {
                    result.putIfAbsent(field, value.asText());
                }
            }
            return result;
        } catch (com.fasterxml.jackson.core.JsonProcessingException invalid) {
            // safeParse already reports deterministic parsing errors to its caller.
            return parsed;
        }
    }

    private static Map<String, String> parseFailure(String raw, String reason) {
        return Map.of(CanonicalEvent.EVENT_MESSAGE, raw.trim(), "parse.error", reason);
    }

    private static Map<String, String> formatMismatch(String raw, String reason) {
        return Map.of(CanonicalEvent.EVENT_MESSAGE, raw.trim(), "parse.error", reason,
                "parse.error.kind", FORMAT_MISMATCH);
    }

    private Map<String, String> safeParse(EventParser p, String raw) {
        try {
            return p.parse(raw);
        } catch (IllegalArgumentException e) {
            // 格式是这种但内容坏：记录但不中断整批（由调用方计入 parse failure）
            return Map.of(CanonicalEvent.EVENT_MESSAGE, raw.trim(), "parse.error", e.getMessage());
        }
    }

    public List<String> parserNames() {
        return parsers.stream().map(EventParser::name).toList();
    }
}
