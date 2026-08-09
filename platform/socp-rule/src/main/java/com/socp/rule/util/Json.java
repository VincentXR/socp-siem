package com.socp.rule.util;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

/**
 * JSON 解析（由 com.siem 迁移，改用 BOM 管理的 Jackson）。
 * 解析结果类型：对象→LinkedHashMap、数组→ArrayList、字符串→String、
 * 整数→Integer/Long、小数→Double、布尔→Boolean、空→null。
 */
public final class Json {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Json() {
    }

    public static Object parse(String text) {
        try {
            return MAPPER.readValue(text, Object.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("JSON 解析失败: " + e.getMessage(), e);
        }
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String text) {
        Object v = parse(text);
        if (!(v instanceof Map)) {
            throw new IllegalArgumentException("JSON 顶层不是对象");
        }
        return (Map<String, Object>) v;
    }

    /** 暴露底层 mapper（如需要自定义序列化/反序列化时）。 */
    public static ObjectMapper mapper() {
        return MAPPER;
    }
}
