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
 * @param status one of {@link CaseState}
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
        Instant updatedAt,
        long rowVersion,
        long ruleCount,
        long alarmCount) {

    /** Source-compatible constructor for callers that do not carry association counts. */
    public Case(String id, String caseNo, String title, String entity, String severity, String status,
                List<String> ruleIds, List<String> alarmIds, List<TimelineEvent> timeline,
                String assignee, Instant createdAt, Instant updatedAt, long rowVersion) {
        this(id, caseNo, title, entity, severity, status, ruleIds, alarmIds, timeline,
                assignee, createdAt, updatedAt, rowVersion,
                ruleIds == null ? 0 : ruleIds.size(), alarmIds == null ? 0 : alarmIds.size());
    }

    /** Source-compatible constructor for callers that do not carry persistence version metadata. */
    public Case(String id, String caseNo, String title, String entity, String severity, String status,
                List<String> ruleIds, List<String> alarmIds, List<TimelineEvent> timeline,
                String assignee, Instant createdAt, Instant updatedAt) {
        this(id, caseNo, title, entity, severity, status, ruleIds, alarmIds, timeline,
                assignee, createdAt, updatedAt, 0L,
                ruleIds == null ? 0 : ruleIds.size(), alarmIds == null ? 0 : alarmIds.size());
    }

    private static final DateTimeFormatter CASE_NO_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

    public static Case create(String title, String entity, String severity) {
        return create(title, entity, severity, null);
    }

    public static Case create(String title, String entity, String severity, String assignee) {
        String uuid = com.socp.incident.web.util.Uuid7.next();
        // 展示编号的随机段取自 UUIDv7 的随机尾段，保证同一毫秒内也不重复
        String suffix = uuid.replace("-", "").substring(20, 26).toUpperCase();
        String caseNo = "INC-" + LocalDate.now().format(CASE_NO_DATE) + "-" + suffix;
        Instant now = Instant.now();
        return new Case(uuid, caseNo, title, entity, severity, "OPEN",
                List.of(), List.of(), List.of(), assignee, now, now, 0L, 0, 0);
    }

    public Case withAdded(String ruleId, String alarmId, TimelineEvent ev) {
        return withAdded(ruleId, alarmId, ev, severity);
    }

    public Case withAdded(String ruleId, String alarmId, TimelineEvent ev, String incomingSeverity) {
        List<String> rules = appendDistinct(ruleIds, ruleId);
        List<String> alarms = appendDistinct(alarmIds, alarmId);
        List<TimelineEvent> tl = new java.util.ArrayList<>(timeline);
        tl.add(ev);
        tl.sort(java.util.Comparator.comparing(TimelineEvent::ts));
        // Keep the most recent timeline events bounded to prevent JSON bloat during alert storms
        if (tl.size() > 500) {
            tl = new java.util.ArrayList<>(tl.subList(tl.size() - 500, tl.size()));
        }
        return new Case(id, caseNo, title, entity, maxSeverity(severity, incomingSeverity), status,
                rules, alarms, List.copyOf(tl),
                assignee, createdAt, Instant.now(), rowVersion,
                ruleCount + (rules.size() > ruleIds.size() ? 1 : 0),
                alarmCount + (alarms.size() > alarmIds.size() ? 1 : 0));
    }

    public Case withStatus(String status, String assignee) {
        return new Case(id, caseNo, title, entity, severity, status,
                ruleIds, alarmIds, timeline, assignee, createdAt, Instant.now(), rowVersion,
                ruleCount, alarmCount);
    }

    private static List<String> appendDistinct(List<String> src, String v) {
        if (v == null) return src;
        List<String> out = new java.util.ArrayList<>(src);
        if (!out.contains(v)) out.add(v);
        return List.copyOf(out);
    }

    private static String maxSeverity(String current, String incoming) {
        List<String> order = List.of("INFO", "LOW", "MEDIUM", "HIGH", "CRITICAL");
        String left = current == null ? "INFO" : current.trim().toUpperCase(java.util.Locale.ROOT);
        String right = incoming == null ? "INFO" : incoming.trim().toUpperCase(java.util.Locale.ROOT);
        int leftRank = order.indexOf(left);
        int rightRank = order.indexOf(right);
        if (leftRank < 0) leftRank = 0;
        if (rightRank < 0) rightRank = 0;
        return order.get(Math.max(leftRank, rightRank));
    }
}
