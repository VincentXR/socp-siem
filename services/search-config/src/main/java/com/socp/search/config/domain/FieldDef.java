package com.socp.search.config.domain;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.UUID;

/**
 * 字段字典（Field Dictionary / Schema）——定义平台统一字段语义：
 * 字段名、中文标签、类型、来源、索引策略。解析/检索/告警共用同一套字段定义，
 * 避免"同义不同名"（如 src_ip vs source_ip）。
 *
 * <p>fieldType：string / int / long / float / ip / date / bool / json
 * source：system（平台内置字段）/ parse（解析产生）/ custom（用户自定义）
 */
public record FieldDef(
        String id,
        @NotBlank @Size(max = 128)
        @Pattern(regexp = "[A-Za-z_][A-Za-z0-9_.-]*")
        String fieldName,
        @NotBlank @Size(max = 128)
        String fieldLabel,
        @NotBlank @Size(max = 32)
        String fieldType,
        @NotBlank @Size(max = 32)
        String source,
        boolean searchable,
        boolean aggregatable,
        boolean stored,
        @Size(max = 2000) String description,
        Instant createdAt
) {
    public static FieldDef create(String fieldName, String fieldLabel, String fieldType,
                                  String source, boolean searchable, boolean aggregatable,
                                  boolean stored, String description) {
        return new FieldDef(UUID.randomUUID().toString(), fieldName, fieldLabel, fieldType,
                source, searchable, aggregatable, stored, description, Instant.now());
    }
}
