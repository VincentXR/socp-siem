package com.socp.alert.service;

import com.socp.alert.persistence.entity.DispositionEntity;
import com.socp.alert.repository.DispositionRepository;


import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.socp.platform.error.exception.ApiException;
import com.socp.platform.tenant.context.TenantContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 告警处置（工单化）：状态流转 + 备注 + 分配人 + 操作历史。
 *
 * <p>The database is the sole authority, so every instance observes the same
 * disposition state and a failed write cannot leak into a process-local view.</p>
 *
 * <p>状态机：OPEN → INVESTIGATING → RESOLVED / CLOSED（可回退）。
 */
@Service
public class AlarmDispositionService {

    public record Disposition(
            String status,
            String assignee,
            List<Note> notes
    ) {
        public record Note(String author, String content, Instant at) {
        }
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<List<Disposition.Note>> NOTES_TYPE = new TypeReference<>() {
    };

    private final DispositionRepository repo;

    public AlarmDispositionService(DispositionRepository repo) {
        this.repo = repo;
    }

    @Transactional(readOnly = true)
    public Disposition get(String alarmId) {
        return repo.findByAlarmIdAndTenantId(alarmId, tenant())
                .map(AlarmDispositionService::toDisposition)
                .orElseGet(() -> new Disposition("OPEN", null, List.of()));
    }

    @Transactional
    public Disposition setStatus(String alarmId, String status) {
        String s = status == null ? "" : status.trim().toUpperCase(Locale.ROOT);
        if (!List.of("OPEN", "INVESTIGATING", "RESOLVED", "CLOSED").contains(s)) {
            throw ApiException.badRequest("非法状态: " + status + "（可选 OPEN/INVESTIGATING/RESOLVED/CLOSED）");
        }
        Disposition cur = currentForUpdate(alarmId);
        Disposition next = new Disposition(s, cur.assignee(), cur.notes());
        return persist(alarmId, next);
    }

    @Transactional
    public Disposition assign(String alarmId, String assignee) {
        Disposition cur = currentForUpdate(alarmId);
        Disposition next = new Disposition(cur.status(), assignee, cur.notes());
        return persist(alarmId, next);
    }

    @Transactional
    public Disposition addNote(String alarmId, String author, String content) {
        if (content == null || content.isBlank()) {
            throw ApiException.badRequest("备注内容不能为空");
        }
        Disposition cur = currentForUpdate(alarmId);
        List<Disposition.Note> notes = new ArrayList<>(cur.notes());
        notes.add(new Disposition.Note(author == null ? "operator" : author, content.trim(), Instant.now()));
        Disposition next = new Disposition(cur.status(), cur.assignee(), List.copyOf(notes));
        return persist(alarmId, next);
    }

    /**
     * Apply one bounded triage mutation to multiple alarms.  IDs are
     * de-duplicated and locked in lexical order to avoid lock inversion when
     * two analysts update overlapping selections concurrently.  The returned
     * item list is deterministic, which also makes audit/retry evidence easy
     * to compare.
     */
    @Transactional
    public Map<String, Object> batchUpdate(List<String> alarmIds, String status,
                                           String assignee, String reason) {
        if (alarmIds == null || alarmIds.isEmpty() || alarmIds.size() > 500) {
            throw ApiException.badRequest("alarmIds must contain between 1 and 500 items");
        }
        Set<String> normalizedIds = new LinkedHashSet<>();
        for (String id : alarmIds) {
            if (id == null || id.isBlank() || id.trim().length() > 255) {
                throw ApiException.badRequest("alarm id must not be blank or longer than 255 characters");
            }
            normalizedIds.add(id.trim());
        }
        if (normalizedIds.isEmpty()) {
            throw ApiException.badRequest("alarmIds must contain at least one non-blank id");
        }
        String normalizedStatus = normalizeOptionalStatus(status);
        String normalizedAssignee = normalizeOptional(assignee);
        String normalizedReason = normalizeOptional(reason);
        if (normalizedStatus == null && normalizedAssignee == null && normalizedReason == null) {
            throw ApiException.badRequest("at least one of status, assignee or reason is required");
        }

        List<Map<String, Object>> items = new ArrayList<>();
        normalizedIds.stream().sorted(Comparator.naturalOrder()).forEach(alarmId -> {
            Disposition current = currentForUpdate(alarmId);
            List<Disposition.Note> notes = new ArrayList<>(current.notes());
            if (normalizedReason != null) {
                notes.add(new Disposition.Note("operator", normalizedReason, Instant.now()));
            }
            Disposition next = new Disposition(
                    normalizedStatus == null ? current.status() : normalizedStatus,
                    normalizedAssignee == null ? current.assignee() : normalizedAssignee,
                    List.copyOf(notes));
            persist(alarmId, next);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("alarmId", alarmId);
            item.put("status", next.status());
            item.put("assignee", next.assignee());
            item.put("reasonRecorded", normalizedReason != null);
            items.add(item);
        });
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("updated", items.size());
        result.put("alarmIds", items.stream().map(item -> String.valueOf(item.get("alarmId"))).toList());
        result.put("items", List.copyOf(items));
        return result;
    }

    /** Persist the complete disposition in the authoritative tenant row. */
    private Disposition persist(String alarmId, Disposition d) {
        String tenant = tenant();
        DispositionEntity e = repo.findByAlarmIdAndTenantId(alarmId, tenant).orElseGet(() -> {
            DispositionEntity n = new DispositionEntity();
            n.setAlarmId(alarmId);
            n.setTenantId(tenant);
            return n;
        });
        e.setStatus(d.status());
        e.setAssignee(d.assignee());
        e.setNotes(writeNotes(d.notes()));
        repo.save(e);
        return d;
    }

    private Disposition currentForUpdate(String alarmId) {
        return repo.findForUpdate(alarmId, tenant())
                .map(AlarmDispositionService::toDisposition)
                .orElseGet(() -> new Disposition("OPEN", null, List.of()));
    }

    private static String tenant() {
        return TenantContext.require();
    }

    private static Disposition toDisposition(DispositionEntity e) {
        return new Disposition(
                e.getStatus() == null ? "OPEN" : e.getStatus(),
                e.getAssignee(),
                readNotes(e.getNotes()));
    }

    private static String writeNotes(List<Disposition.Note> notes) {
        try {
            return MAPPER.writeValueAsString(notes);
        } catch (Exception ex) {
            return "[]";
        }
    }

    private static String normalizeOptionalStatus(String status) {
        String normalized = normalizeOptional(status);
        if (normalized == null) return null;
        normalized = normalized.toUpperCase(Locale.ROOT);
        if (!List.of("OPEN", "INVESTIGATING", "RESOLVED", "CLOSED").contains(normalized)) {
            throw ApiException.badRequest("非法状态: " + status + "（可选 OPEN/INVESTIGATING/RESOLVED/CLOSED）");
        }
        return normalized;
    }

    private static String normalizeOptional(String value) {
        if (value == null || value.isBlank()) return null;
        return value.trim();
    }

    private static List<Disposition.Note> readNotes(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            List<Disposition.Note> n = MAPPER.readValue(json, NOTES_TYPE);
            return n == null ? List.of() : n;
        } catch (Exception ex) {
            return List.of();
        }
    }
}
