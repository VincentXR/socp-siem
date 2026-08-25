package com.socp.alert.service;

import com.socp.alert.api.controller.*;
import com.socp.alert.api.request.*;
import com.socp.alert.domain.*;
import com.socp.alert.persistence.entity.DispositionEntity;
import com.socp.alert.repository.*;
import com.socp.alert.service.*;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.socp.platform.error.exception.ApiException;
import com.socp.platform.tenant.context.TenantContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

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
        String s = status == null ? "" : status.trim().toUpperCase();
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
        String tenant = TenantContext.get();
        return tenant == null || tenant.isBlank() ? "default" : tenant;
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
