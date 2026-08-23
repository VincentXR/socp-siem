package com.socp.incident.web.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.socp.incident.web.domain.Case;
import com.socp.incident.web.domain.TimelineEvent;
import com.socp.incident.web.store.CaseStore;
import com.socp.incident.web.store.AlarmCaseLinkEntity;
import com.socp.incident.web.store.AlarmCaseLinkRepository;
import com.socp.platform.tenant.TenantContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

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
    private final AlarmCaseLinkRepository alarmLinks;

    /** 归档导出：全部案件（含时间线）序列化为 JSON。 */
    public String exportJson() {
        try {
            return MAPPER.writeValueAsString(store.list());
        } catch (Exception e) {
            return "[]";
        }
    }

    public CaseService(CaseStore store, AlarmCaseLinkRepository alarmLinks) {
        this.store = store;
        this.alarmLinks = alarmLinks;
    }

    /** 由告警自动建案/归并。alarm 至少含 id/ruleId/ruleName/severity/entity/message/occurredAt。 */
    @Transactional
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

        if (!alarmId.isBlank()) {
            var existingLink = alarmLinks.findByTenantIdAndAlarmId(tenant(), alarmId);
            if (existingLink.isPresent()) {
                Case linked = store.get(existingLink.get().getCaseId());
                if (linked != null) return response(linked, false, true);
            }
        }

        String existingId = store.openCaseId(entity);
        Case c;
        if (existingId != null) {
            Case open = store.get(existingId);
            // 幂等：同一告警可能被 alert-web 与 SOAR 剧本重复推送，已归并过则原样返回，避免时间线重复
            if (!alarmId.isBlank() && open.alarmIds().contains(alarmId)) {
                rememberAlarm(alarmId, open.id());
                return response(open, false, true);
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
        rememberAlarm(alarmId, c.id());
        return response(c, existingId == null, false);
    }

    private void rememberAlarm(String alarmId, String caseId) {
        if (alarmId == null || alarmId.isBlank()) return;
        String tenant = tenant();
        AlarmCaseLinkEntity link = new AlarmCaseLinkEntity();
        link.setId(UUID.nameUUIDFromBytes((tenant + "\u0000" + alarmId).getBytes(StandardCharsets.UTF_8)).toString());
        link.setTenantId(tenant);
        link.setAlarmId(alarmId);
        link.setCaseId(caseId);
        link.setCreatedAt(Instant.now());
        alarmLinks.save(link);
    }

    private static Map<String, Object> response(Case incident, boolean created, boolean duplicate) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("caseId", incident.id());
        out.put("caseNo", incident.caseNo());
        out.put("title", incident.title());
        out.put("entity", incident.entity());
        out.put("status", incident.status());
        out.put("alarmCount", incident.alarmIds().size());
        out.put("created", created);
        if (duplicate) out.put("duplicate", true);
        return out;
    }

    private static String tenant() {
        String tenant = TenantContext.get();
        return tenant == null || tenant.isBlank() ? "default" : tenant;
    }

    public List<Case> list() {
        return store.list();
    }

    /** 手动创建案件：不关联告警，后续可在调查过程中补充时间线和关联信息。 */
    public Case create(String title, String entity, String severity, String assignee) {
        Case created = Case.create(title.trim(), entity == null ? "" : entity.trim(),
                severity == null || severity.isBlank() ? "HIGH" : severity.trim().toUpperCase(),
                assignee == null || assignee.isBlank() ? null : assignee.trim());
        return store.save(created);
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
