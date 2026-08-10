package com.socp.incident.web.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.socp.incident.web.domain.Case;
import com.socp.incident.web.domain.TimelineEvent;
import com.socp.incident.web.store.CaseStore;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 案件服务：把告警归并为案件并维护调查时间线。
 * 同一实体（IP/主机/用户）的告警进入同一进行中案件；不同实体新建案件。
 */
@Service
public class CaseService {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private final CaseStore store;

    /** 归档导出：全部案件（含时间线）序列化为 JSON。 */
    public String exportJson() {
        try {
            return MAPPER.writeValueAsString(store.list());
        } catch (Exception e) {
            return "[]";
        }
    }

    public CaseService(CaseStore store) {
        this.store = store;
    }

    /** 由告警自动建案/归并。alarm 至少含 id/ruleId/ruleName/severity/entity/message/occurredAt。 */
    public Map<String, Object> fromAlarm(Map<String, Object> alarm) {
        String entity = str(alarm, "entity");
        String alarmId = str(alarm, "id");
        String ruleId = str(alarm, "ruleId");
        String title = str(alarm, "ruleName");
        String severity = str(alarm, "severity");
        String message = str(alarm, "message");
        String mitre = str(alarm, "mitre");
        String tsStr = str(alarm, "occurredAt");
        Instant ts = parseTs(tsStr);

        String existingId = store.openCaseId(entity);
        Case c;
        if (existingId != null) {
            Case open = store.get(existingId);
            // 幂等：同一告警可能被 alert-web 与 SOAR 剧本重复推送，已归并过则原样返回，避免时间线重复
            if (!alarmId.isBlank() && open.alarmIds().contains(alarmId)) {
                Map<String, Object> dup = new LinkedHashMap<>();
                dup.put("caseId", open.id());
                dup.put("caseNo", open.caseNo());
                dup.put("title", open.title());
                dup.put("entity", open.entity());
                dup.put("status", open.status());
                dup.put("alarmCount", open.alarmIds().size());
                dup.put("created", false);
                dup.put("duplicate", true);
                return dup;
            }
            TimelineEvent ev = new TimelineEvent(ts, "ALARM",
                    ruleId + (mitre.isEmpty() ? "" : " [" + mitre + "]") + ": " + message, "detection", alarmId);
            c = open.withAdded(ruleId, alarmId, ev);
            store.save(c);
        } else {
            String t = (entity == null || entity.isBlank())
                    ? ("事件: " + (title.isEmpty() ? alarmId : title))
                    : ("实体 " + entity + " 相关告警");
            c = Case.create(t, entity, severity);
            TimelineEvent ev = new TimelineEvent(ts, "ALARM",
                    ruleId + (mitre.isEmpty() ? "" : " [" + mitre + "]") + ": " + message, "detection", alarmId);
            c = c.withAdded(ruleId, alarmId, ev);
            store.save(c);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("caseId", c.id());
        out.put("caseNo", c.caseNo());
        out.put("title", c.title());
        out.put("entity", c.entity());
        out.put("status", c.status());
        out.put("alarmCount", c.alarmIds().size());
        out.put("created", existingId == null);
        return out;
    }

    public List<Case> list() {
        return store.list();
    }

    public Case get(String id) {
        return store.get(id);
    }

    public Map<String, Object> setStatus(String id, String status, String assignee) {
        Case c = store.get(id);
        if (c == null) return Map.of("error", "not_found");
        Case updated = c.withStatus(status, assignee);
        store.save(updated);
        return Map.of("case", updated);
    }

    public Map<String, Object> addNote(String id, String author, String content) {
        Case c = store.get(id);
        if (c == null) return Map.of("error", "not_found");
        TimelineEvent ev = new TimelineEvent(Instant.now(), "NOTE", author + ": " + content, "analyst", null);
        Case updated = new Case(c.id(), c.caseNo(), c.title(), c.entity(), c.severity(), c.status(),
                c.ruleIds(), c.alarmIds(),
                append(c.timeline(), ev), c.assignee(), c.createdAt(), Instant.now());
        store.save(updated);
        return Map.of("case", updated);
    }

    public Map<String, Object> stats() {
        Map<String, Object> out = new LinkedHashMap<>();
        List<Case> all = store.list();
        long open = all.stream().filter(c -> "OPEN".equals(c.status()) || "INVESTIGATING".equals(c.status())).count();
        out.put("total", all.size());
        out.put("open", open);
        out.put("resolved", all.size() - open);
        return out;
    }

    private static List<TimelineEvent> append(List<TimelineEvent> src, TimelineEvent e) {
        List<TimelineEvent> out = new java.util.ArrayList<>(src);
        out.add(e);
        out.sort(java.util.Comparator.comparing(TimelineEvent::ts));
        return List.copyOf(out);
    }

    private static String str(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v == null ? "" : String.valueOf(v);
    }

    private static Instant parseTs(String s) {
        if (s == null || s.isBlank()) return Instant.now();
        try {
            return Instant.parse(s);
        } catch (Exception e) {
            return Instant.now();
        }
    }
}
