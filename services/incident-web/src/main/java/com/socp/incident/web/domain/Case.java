package com.socp.incident.web.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 安全案件（Incident / Case）：把同一实体（IP/主机/用户）相关的多条告警归并，
 * 形成可供 SOC 调查处置的单元，并维护一条事件时间线。
 *
 * <p>身份标识分两层：{@code id} 是内部主键（UUIDv7，不可读但唯一、有序），
 * {@code caseNo} 是给人看的展示编号（{@code INC-<yyyyMMdd>-<6位随机>}，同一毫秒建案不会撞）。
 * 旧实现把主键直接写成 {@code CASE-<epochMilli>}，并发建案会主键冲突——这是被修掉的根因。
 *
 * @param status OPEN / INVESTIGATING / CONTAINED / RESOLVED / CLOSED
 */
public record Case(
        String id,
        String caseNo,
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

    private static final DateTimeFormatter CASE_NO_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

    public static Case create(String title, String entity, String severity) {
        String uuid = com.socp.incident.web.util.Uuid7.next();
        // 展示编号的随机段取自 UUIDv7 的随机尾段，保证同一毫秒内也不重复
        String suffix = uuid.replace("-", "").substring(20, 26).toUpperCase();
        String caseNo = "INC-" + LocalDate.now().format(CASE_NO_DATE) + "-" + suffix;
        Instant now = Instant.now();
        return new Case(uuid, caseNo, title, entity, severity, "OPEN",
                List.of(), List.of(), List.of(), null, now, now);
    }

    public Case withAdded(String ruleId, String alarmId, TimelineEvent ev) {
        List<String> rules = appendDistinct(ruleIds, ruleId);
        List<String> alarms = appendDistinct(alarmIds, alarmId);
        List<TimelineEvent> tl = new java.util.ArrayList<>(timeline);
        tl.add(ev);
        tl.sort(java.util.Comparator.comparing(TimelineEvent::ts));
        return new Case(id, caseNo, title, entity, severity, status, rules, alarms, List.copyOf(tl),
                assignee, createdAt, Instant.now());
    }

    public Case withStatus(String status, String assignee) {
        return new Case(id, caseNo, title, entity, severity, status,
                ruleIds, alarmIds, timeline, assignee, createdAt, Instant.now());
    }

    private static List<String> appendDistinct(List<String> src, String v) {
        if (v == null) return src;
        List<String> out = new java.util.ArrayList<>(src);
        if (!out.contains(v)) out.add(v);
        return List.copyOf(out);
    }
}
