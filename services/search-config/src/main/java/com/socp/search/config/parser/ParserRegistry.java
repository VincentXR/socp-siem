package com.socp.search.config.parser;

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
public class ParserRegistry {

    private final List<EventParser> parsers;
    private final Map<String, EventParser> byVendor = new LinkedHashMap<>();

    public ParserRegistry() {
        parsers = List.of(
                new SysmonParser(),
                new FalcoParser(),
                new AuditdParser(),
                new JsonParser(),
                new SyslogParser(),
                new CefParser(),
                new LeefParser(),
                new KvParser());
        byVendor.put("sysmon", new SysmonParser());
        byVendor.put("falco", new FalcoParser());
        byVendor.put("auditd", new AuditdParser());
        byVendor.put("json", new JsonParser());
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
        if (raw == null || raw.isBlank()) {
            return Map.of();
        }
        if (vendorHint != null && !vendorHint.isBlank()) {
            EventParser p = byVendor.get(vendorHint.trim().toLowerCase());
            if (p != null) {
                Map<String, String> out = safeParse(p, raw);
                if (out != null) {
                    return out;
                }
            }
        }
        // 特征路由：Sysmon JSON（Event/EventData）→ JSON → syslog → CEF → LEEF → KV
        for (EventParser p : parsers) {
            Map<String, String> out = safeParse(p, raw);
            if (out != null) {
                return out;
            }
        }
        // 兜底：原文进 event.message
        return Map.of(CanonicalEvent.EVENT_MESSAGE, raw.trim());
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
