package com.socp.soar.web.model;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * SOAR 剧本——当告警匹配触发条件时按 actions 依次执行（Temporal Saga 编排+补偿）。
 * 当前为内存态；Temporal 接线后 actions → Saga workflow steps（含补偿逻辑）。
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
