package com.socp.soar.web.model;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * SOAR 剧本——当告警匹配触发条件时按 actions 依次执行。
 * 元数据由 JPA + Flyway 持久化；执行时根据 Temporal 可用性选择
 * Workflow/Activity 或本地补偿执行器。
 */
public record Playbook(
        String id,
        String name,
        String trigger,
        List<String> actions,
        boolean enabled,
        PlaybookStatus status,
        Instant createdAt
) {
    public static Playbook create(String name, String trigger, List<String> actions, boolean enabled) {
        return new Playbook(UUID.randomUUID().toString(), name, trigger,
                List.copyOf(actions), enabled,
                enabled ? PlaybookStatus.ACTIVE : PlaybookStatus.DRAFT,
                Instant.now());
    }
}
