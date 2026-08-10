package com.socp.search.config.parser;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * JSON 解析器：{@code {...}} 行整体展平为字段，再做 canonical 别名映射。
 * 典型输入：Vector/Falco/Sysmon/Suricata JSON 事件、采集器包装行（collector/host/source/msg/src_ip）。
 */
public final class JsonParser implements EventParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public String name() {
        return "json";
    }

    @Override
    public Map<String, String> parse(String raw) {
        if (raw == null || !raw.stripLeading().startsWith("{")) {
            return null;
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> obj = MAPPER.readValue(raw, Map.class);
            Map<String, String> flat = new LinkedHashMap<>();
            flatten("", obj, flat);
            return CanonicalEvent.canonicalize(flat);
        } catch (Exception e) {
            throw new IllegalArgumentException("JSON 解析失败: " + e.getMessage(), e);
        }
    }

    private void flatten(String prefix, Map<String, Object> map, Map<String, String> out) {
        for (var en : map.entrySet()) {
            String key = prefix.isEmpty() ? en.getKey() : prefix + "." + en.getKey();
            Object v = en.getValue();
            if (v instanceof Map<?, ?> nested) {
                @SuppressWarnings("unchecked")
                Map<String, Object> nm = (Map<String, Object>) nested;
                flatten(key, nm, out);
            } else {
                CanonicalEvent.putIf(out, key, v);
            }
        }
    }
}
