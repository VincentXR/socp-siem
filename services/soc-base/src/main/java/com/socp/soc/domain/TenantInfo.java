package com.socp.soc.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * 平台租户/用户摘要——SOC 统一底座管理。
 */
public record TenantInfo(
        String id,
        String name,
        String code,
        int userCount,
        int alarmCount,
        Instant createdAt
) {
    public static TenantInfo create(String name, String code) {
        return new TenantInfo(UUID.randomUUID().toString(), name, code, 0, 0, Instant.now());
    }
}
