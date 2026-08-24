package com.socp.search.config.domain;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.UUID;

/**
 * 日志类别（分类学/Taxonomy）——按业务语义给日志源归类，
 * 用于检索过滤、报表聚合、规则编排（如"认证类"下挂暴力破解规则）。
 * 对齐 MITRE ATT&CK 战术/常见 SIEM 类别体系。
 */
public record LogCategory(
        String id,
        @NotBlank @Size(max = 64)
        String code,
        @NotBlank @Size(max = 128)
        String name,
        @Size(max = 2000)
        String description,
        /** 该类别的默认告警级别基线 */
        @NotBlank @Size(max = 32) String defaultSeverity,
        boolean enabled,
        Instant createdAt
) {
    public static LogCategory create(String code, String name, String description, String defaultSeverity, boolean enabled) {
        return new LogCategory(UUID.randomUUID().toString(), code, name, description, defaultSeverity, enabled, Instant.now());
    }
}
