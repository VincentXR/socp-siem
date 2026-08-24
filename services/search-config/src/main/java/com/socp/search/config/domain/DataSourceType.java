package com.socp.search.config.domain;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.UUID;

/**
 * 数据源分类（接入方式注册表）——元数据管理的一部分。
 * 定义平台支持的所有接入方式及其默认参数，日志源通过 type 关联。
 */
public record DataSourceType(
        String id,
        @NotBlank @Size(max = 64)
        String code,
        @NotBlank @Size(max = 128)
        String name,
        @Size(max = 2000)
        String description,
        boolean enabled,
        Instant createdAt
) {
    public static DataSourceType create(String code, String name, String description, boolean enabled) {
        return new DataSourceType(UUID.randomUUID().toString(), code, name, description, enabled, Instant.now());
    }
}
