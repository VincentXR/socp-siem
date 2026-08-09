package com.socp.incident.web.domain;

import java.time.Instant;
import java.util.List;

/**
 * 安全案件（Incident / Case）：把同一实体（IP/主机/用户）相关的多条告警归并，
 * 形成可供 SOC 调查处置的单元，并维护一条事件时间线。
 *
 * @param status OPEN / INVESTIGATING / CONTAINED / RESOLVED / CLOSED
 */
public record Case(
        String id,
        String title,
        String entity,
        String severity,
        String status,
        List<String> ruleIds,
        List<String> alarmIds,
        List<TimelineEvent> timeline,
        String assignee,
        Instant createdAt,
        Instant updatedAt) {

    public static Case create(String title, String entity, String severity) {
        String id = "CASE-" + Instant.now().toEpochMilli();
        return new Case(id, title, entity, severity, "OPEN",
                List.of(), List.of(), List.of(), null, Instant.now(), Instant.now());
    }

    public Case withAdded(String ruleId, String alarmId, TimelineEvent ev) {
        List<String> rules = appendDistinct(ruleIds, ruleId);
        List<String> alarms = appendDistinct(alarmIds, alarmId);
        List<TimelineEvent> tl = new java.util.ArrayList<>(timeline);
        tl.add(ev);
        tl.sort(java.util.Comparator.comparing(TimelineEvent::ts));
        return new Case(id, title, entity, severity, status, rules, alarms, List.copyOf(tl),
                assignee, createdAt, Instant.now());
    }

    public Case withStatus(String status, String assignee) {
        return new Case(id, title, entity, severity, status,
                ruleIds, alarmIds, timeline, assignee, createdAt, Instant.now());
    }

    private static List<String> appendDistinct(List<String> src, String v) {
        if (v == null) return src;
        List<String> out = new java.util.ArrayList<>(src);
        if (!out.contains(v)) out.add(v);
        return List.copyOf(out);
    }
}
