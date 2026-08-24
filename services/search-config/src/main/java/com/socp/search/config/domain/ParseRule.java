package com.socp.search.config.domain;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 解析规则——定义「一行日志 → 结构化字段」的解析方式。
 *
 * <p>format=REGEX：pattern 用命名分组（如 (?&lt;src_ip&gt;\d+\.\d+\.\d+\.\d+)），
 * mapping 声明「分组名 → 事件字段」映射（缺省直接用分组名作字段名）。
 * format=SYSLOG/JSON/KV/CEF/LEEF：走 SEARCH 内建解析链（与 com.siem ParserChain 一致），
 * pattern/mapping 可做字段重命名与补充。
 *
 * <p>sourceId 为空表示全局规则（所有日志源生效）；指定则仅该日志源生效。
 */
public record ParseRule(
        String id,
        @NotBlank @Size(max = 128)
        String name,
        @Size(max = 64)
        String sourceId,
        @NotBlank @Size(max = 32)
        String format,
        /** REGEX 时为正则（命名分组）；其他格式可为空 */
        @Size(max = 65536) String pattern,
        /** 分组/字段映射：{group, field}；空则用分组名当字段名 */
        @Valid @Size(max = 1000) List<@Valid FieldMapping> mapping,
        /** 命中后强制设置的字段：{field, value} */
        @Valid @Size(max = 1000) List<@Valid FieldMapping> setFields,
        boolean enabled,
        @Min(0) @Max(100000)
        int order,
        Instant createdAt
) {
    public record FieldMapping(
            @NotBlank @Size(max = 128) String group,
            @NotBlank @Size(max = 128) String field,
            @Size(max = 4096) String value) {
    }

    public static ParseRule create(String name, String sourceId, String format,
                                   String pattern, List<FieldMapping> mapping,
                                   List<FieldMapping> setFields, boolean enabled, int order) {
        return createWithId(UUID.randomUUID().toString(), name, sourceId, format,
                pattern, mapping, setFields, enabled, order);
    }

    /** 显式指定 id（种子规则用固定 id 便于被日志源/预览引用） */
    public static ParseRule createWithId(String id, String name, String sourceId, String format,
                                         String pattern, List<FieldMapping> mapping,
                                         List<FieldMapping> setFields, boolean enabled, int order) {
        return new ParseRule(id, name, sourceId, format, pattern,
                mapping == null ? List.of() : List.copyOf(mapping),
                setFields == null ? List.of() : List.copyOf(setFields),
                enabled, order, Instant.now());
    }
}
