package com.socp.search.config.parser;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * KV 解析器：{@code key=value key2=value2} 行（如常见 syslog 正文、firewall 日志）。
 * 值支持引号包裹；= 两侧空白容忍。输出做 canonical 别名映射。
 */
public final class KvParser implements EventParser {

    @Override
    public String name() {
        return "kv";
    }

    @Override
    public Map<String, String> parse(String raw) {
        if (raw == null || !raw.contains("=")) {
            return null;
        }
        Map<String, String> kv = new LinkedHashMap<>();
        int i = 0;
        int n = raw.length();
        while (i < n) {
            while (i < n && Character.isWhitespace(raw.charAt(i))) i++;
            int eq = raw.indexOf('=', i);
            if (eq < 0) {
                break;
            }
            String key = raw.substring(i, eq).trim();
            if (key.isEmpty()) {
                i = eq + 1;
                continue;
            }
            i = eq + 1;
            while (i < n && Character.isWhitespace(raw.charAt(i))) i++;
            String value;
            if (i < n && raw.charAt(i) == '"') {
                int end = raw.indexOf('"', i + 1);
                if (end < 0) {
                    value = raw.substring(i + 1);
                    i = n;
                } else {
                    value = raw.substring(i + 1, end);
                    i = end + 1;
                }
            } else {
                int sp = i;
                while (sp < n && !Character.isWhitespace(raw.charAt(sp))) sp++;
                value = raw.substring(i, sp);
                i = sp;
            }
            CanonicalEvent.putIf(kv, key, value);
        }
        return kv.isEmpty() ? null : CanonicalEvent.canonicalize(kv);
    }
}
