package com.socp.search.config.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.socp.search.config.domain.ParseRule;
import com.socp.search.config.persistence.store.ParseRuleStore;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 解析预览——在配置页里用一条示例日志验证解析规则能否抽出期望字段。
 * 支持 REGEX（命名分组）/ JSON（Jackson 展平）/ KV（key=value）。
 * SYSLOG/CEF/LEEF 由 SEARCH 内建解析链负责，此处预览给出格式说明。
 */
@Service
public class ParsePreviewService {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern NAMED_GROUP = Pattern.compile("\\(\\?<([A-Za-z][A-Za-z0-9_]*)>");
    private final ParseRuleStore store;

    public ParsePreviewService(ParseRuleStore store) {
        this.store = store;
    }

    /**
     * 预览解析结果。
     *
     * @param ruleId 已有规则 ID（优先）；或直接给 format/pattern
     * @param format 解析格式（ruleId 为空时使用）
     * @param pattern 正则（format=REGEX 且 ruleId 为空时使用）
     * @param line 示例日志行
     */
    public Map<String, Object> preview(String ruleId, String format, String pattern, String line) {
        ParseRule rule = ruleId != null && !ruleId.isBlank() ? store.get(ruleId) : null;
        String fmt = rule != null ? rule.format() : (format == null ? "REGEX" : format);
        String pat = rule != null ? rule.pattern() : pattern;
        List<ParseRule.FieldMapping> mapping = rule != null ? rule.mapping() : List.of();
        List<ParseRule.FieldMapping> setFields = rule != null ? rule.setFields() : List.of();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("rule", rule == null ? "(临时)" : rule.name());
        result.put("format", fmt);
        result.put("matched", false);
        result.put("fields", Map.of());

        try {
            Map<String, Object> fields = switch (fmt.toUpperCase()) {
                case "JSON" -> parseJson(line);
                case "KV" -> parseKv(line);
                case "REGEX" -> parseRegex(pat, line, mapping);
                default -> Map.of("_note", "SYSLOG/CEF/LEEF 由 SEARCH 内建解析链处理，预览支持 REGEX/JSON/KV");
            };
            if (!setFields.isEmpty()) {
                Map<String, Object> merged = new LinkedHashMap<>(fields);
                for (ParseRule.FieldMapping f : setFields) {
                    if (f.field() != null && f.value() != null) merged.put(f.field(), f.value());
                }
                fields = merged;
            }
            result.put("matched", !fields.isEmpty() && !fields.containsKey("_note"));
            result.put("fields", fields);
        } catch (Exception e) {
            result.put("error", e.getMessage());
        }
        return result;
    }

    private Map<String, Object> parseJson(String line) throws Exception {
        @SuppressWarnings("unchecked")
        Map<String, Object> raw = MAPPER.readValue(line, Map.class);
        Map<String, Object> flat = new LinkedHashMap<>();
        flatten("", raw, flat);
        return flat;
    }

    private void flatten(String prefix, Map<String, Object> map, Map<String, Object> out) {
        map.forEach((k, v) -> {
            String key = prefix.isEmpty() ? k : prefix + "." + k;
            if (v instanceof Map<?, ?> nested) {
                @SuppressWarnings("unchecked")
                Map<String, Object> nm = (Map<String, Object>) nested;
                flatten(key, nm, out);
            } else {
                out.put(key, String.valueOf(v));
            }
        });
    }

    private Map<String, Object> parseKv(String line) {
        Map<String, Object> out = new LinkedHashMap<>();
        Matcher m = Pattern.compile("(\\w+)=(\"[^\"]*\"|'[^']*'|\\S+)").matcher(line);
        while (m.find()) {
            String v = m.group(2);
            if ((v.startsWith("\"") && v.endsWith("\"")) || (v.startsWith("'") && v.endsWith("'"))) {
                v = v.substring(1, v.length() - 1);
            }
            out.put(m.group(1), v);
        }
        return out;
    }

    private Map<String, Object> parseRegex(String pattern, String line, List<ParseRule.FieldMapping> mapping) {
        if (pattern == null || pattern.isBlank()) {
            return Map.of("_note", "REGEX 需提供 pattern（命名分组）");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        Matcher m = Pattern.compile(pattern).matcher(line);
        if (!m.find()) {
            return Map.of();
        }
        // 扫描 pattern 提取命名分组名（Java 的 Matcher 不直接暴露组名）
        List<String> names = new ArrayList<>();
        Matcher nm = NAMED_GROUP.matcher(pattern);
        while (nm.find()) names.add(nm.group(1));

        if (names.isEmpty()) {
            // 无命名分组：用 mapping 的数字索引取
            for (ParseRule.FieldMapping f : mapping) {
                if (f.group() != null && f.group().matches("\\d+")) {
                    int idx = Integer.parseInt(f.group());
                    if (idx >= 1 && idx <= m.groupCount() && m.group(idx) != null) {
                        out.put(f.field() == null ? f.group() : f.field(), m.group(idx));
                    }
                }
            }
            if (out.isEmpty()) {
                out.put("_note", "正则匹配但无命名分组；建议用 (?<name>...) 或 mapping 数字索引");
            }
            return out;
        }
        for (String name : names) {
            try {
                String val = m.group(name);
                if (val != null) out.put(mapField(mapping, name), val);
            } catch (IllegalArgumentException ignored) {
                // 组名在本次匹配中不存在，跳过
            }
        }
        return out;
    }

    private static String mapField(List<ParseRule.FieldMapping> mapping, String group) {
        for (ParseRule.FieldMapping f : mapping) {
            if (group.equals(f.group()) && f.field() != null) {
                return f.field();
            }
        }
        return group;
    }
}
