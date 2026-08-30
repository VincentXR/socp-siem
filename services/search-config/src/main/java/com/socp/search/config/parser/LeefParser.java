package com.socp.search.config.parser;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * LEEF（Log Event Extended Format）解析器：IBM QRadar 风格。
 *
 * <pre>
 * LEEF:1.0|Vendor|Product|Version|EventID|Name|Severity|Extension
 * LEEF:1.0|Microsoft|Sysmon|10.0|1|Process creation|5|src=1.2.3.4 dst=5.6.7.8 sev=5
 * </pre>
 *
 * 输出与 CEF 对齐：event.code（EventID）、event.action（Name）、event.severity、
 * vendor/product/version 标签；Extension（key=value）走 canonical 映射。
 */
public final class LeefParser implements EventParser {

    @Override
    public String name() {
        return "leef";
    }

    @Override
    public Map<String, String> parse(String raw) {
        if (raw == null || !raw.startsWith("LEEF:")) {
            return null;
        }
        String body = raw.substring("LEEF:".length());
        String[] parts = body.split("\\|", 8);
        if (parts.length < 7) {
            throw new IllegalArgumentException("LEEF 段数不足: " + parts.length);
        }
        Map<String, String> out = new LinkedHashMap<>();
        out.put("vendor", "leef");
        out.put("leef.device.vendor", parts[1].trim());
        out.put("leef.device.product", parts[2].trim());
        out.put("leef.device.version", parts[3].trim());
        out.put(CanonicalEvent.EVENT_CODE, parts[4].trim());
        out.put(CanonicalEvent.EVENT_ACTION, parts[5].trim());
        String sev = parts[6].trim();
        try {
            int v = Integer.parseInt(sev);
            out.put(CanonicalEvent.EVENT_SEVERITY, v <= 3 ? "HIGH" : v <= 6 ? "MEDIUM" : v <= 8 ? "LOW" : "INFO");
        } catch (NumberFormatException e) {
            out.put(CanonicalEvent.EVENT_SEVERITY, sev.isEmpty() ? "INFO" : sev);
        }
        String ext = parts.length > 7 ? parts[7].trim() : "";
        if (!ext.isEmpty()) {
            // LEEF extension 用 \t 分隔（QRadar 标准），也容忍空格分隔
            String normalized = ext.contains("\t") ? ext.replace('\t', ' ') : ext;
            Map<String, String> kv = new KvParser().parse(normalized);
            if (kv != null) {
                // LEEF uses the same short network/user extension names as CEF.
                // Route them through the dedicated extension mapper so src/dst,
                // ports and protocol remain canonical instead of leaking as
                // vendor-specific keys (e.g. "src" or "cef.network.protocol").
                out.putAll(CanonicalEvent.cefExt(kv));
            }
        }
        return out;
    }
}
