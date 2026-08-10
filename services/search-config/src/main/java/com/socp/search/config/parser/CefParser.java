package com.socp.search.config.parser;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * CEF（Common Event Format）解析器：ArcSight 风格，常见于防火墙/IPS/WAF 设备。
 *
 * <pre>
 * CEF:Version|Device Vendor|Device Product|Device Version|Signature ID|Name|Severity|Extension
 * CEF:0|Fortinet|FortiGate|v7.2|52002|traffic-log|5|src=1.2.3.4 dst=5.6.7.8 proto=tcp sport=54321 dport=80
 * </pre>
 *
 * 输出：event.code（Signature ID）、event.action（Name）、event.severity（0-10 → CRITICAL..LOW）、
 * vendor/product/version 标签；Extension（key=val）做 canonical 映射（src→source.ip 等）。
 */
public final class CefParser implements EventParser {

    @Override
    public String name() {
        return "cef";
    }

    @Override
    public Map<String, String> parse(String raw) {
        if (raw == null || !raw.startsWith("CEF:")) {
            return null;
        }
        // 管道分隔 8 段：CEF:Version | Vendor | Product | Version | SignatureID | Name | Severity | Extension
        String body = raw.substring("CEF:".length());
        // 注意 Extension 里可能含 |（如 url），用前 7 个 | 切分，剩余全部归 Extension
        String[] parts = body.split("\\|", 8);
        if (parts.length < 7) {
            throw new IllegalArgumentException("CEF 段数不足: " + parts.length);
        }
        Map<String, String> out = new LinkedHashMap<>();
        out.put("vendor", "cef");
        out.put("cef.device.vendor", parts[1].trim());
        out.put("cef.device.product", parts[2].trim());
        out.put("cef.device.version", parts[3].trim());
        out.put(CanonicalEvent.EVENT_CODE, parts[4].trim());
        out.put(CanonicalEvent.EVENT_ACTION, parts[5].trim());
        out.put(CanonicalEvent.EVENT_SEVERITY, cefSeverity(parts[6].trim()));
        String ext = parts.length > 7 ? parts[7].trim() : "";
        if (!ext.isEmpty()) {
            // Extension 用 CEF 的 key 名（src/dst/spt/dpt/proto/cs1...），交给 KV 解析 + canonical 映射
            Map<String, String> kv = new KvParser().parse(ext);
            if (kv != null) {
                out.putAll(CanonicalEvent.cefExt(kv));
            }
        }
        return out;
    }

    /** CEF 严重级 0-10：0-3 高、4-6 中、7-8 低、9-10 信息。 */
    private static String cefSeverity(String s) {
        try {
            int v = Integer.parseInt(s.trim());
            if (v <= 3) return "HIGH";
            if (v <= 6) return "MEDIUM";
            if (v <= 8) return "LOW";
            return "INFO";
        } catch (NumberFormatException e) {
            return s.trim().isEmpty() ? "INFO" : s.trim();
        }
    }
}
