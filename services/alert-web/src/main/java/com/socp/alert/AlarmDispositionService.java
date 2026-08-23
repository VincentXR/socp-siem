package com.socp.alert;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.socp.platform.error.ApiException;
import com.socp.platform.tenant.TenantContext;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 告警处置（工单化）：状态流转 + 备注 + 分配人 + 操作历史。
 *
 * <p>2026-08-12（P3）：从纯内存 {@code ConcurrentHashMap} 升级为「内存 + t_alarm_disposition 双写」——
 * 启动从库恢复、写操作同步落库，重启不丢（此前重启即清空处置/备注）。接口不变。
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
    private final Map<String, Disposition> map = new ConcurrentHashMap<>();

    public AlarmDispositionService(DispositionRepository repo) {
        this.repo = repo;
    }

    @PostConstruct
    void init() {
        for (DispositionEntity e : repo.findAll()) {
            map.put(key(e.getTenantId(), e.getAlarmId()), toDisposition(e));
        }
    }

    public Disposition get(String alarmId) {
        return map.getOrDefault(key(tenant(), alarmId), new Disposition("OPEN", null, List.of()));
    }

    public Disposition setStatus(String alarmId, String status) {
        String s = status == null ? "" : status.trim().toUpperCase();
        if (!List.of("OPEN", "INVESTIGATING", "RESOLVED", "CLOSED").contains(s)) {
            throw ApiException.badRequest("非法状态: " + status + "（可选 OPEN/INVESTIGATING/RESOLVED/CLOSED）");
        }
        Disposition cur = get(alarmId);
        Disposition next = new Disposition(s, cur.assignee(), cur.notes());
        return persist(alarmId, next);
    }

    public Disposition assign(String alarmId, String assignee) {
        Disposition cur = get(alarmId);
        Disposition next = new Disposition(cur.status(), assignee, cur.notes());
        return persist(alarmId, next);
    }

    public Disposition addNote(String alarmId, String author, String content) {
        if (content == null || content.isBlank()) {
            throw ApiException.badRequest("备注内容不能为空");
        }
        Disposition cur = get(alarmId);
        List<Disposition.Note> notes = new ArrayList<>(cur.notes());
        notes.add(new Disposition.Note(author == null ? "operator" : author, content.trim(), Instant.now()));
        Disposition next = new Disposition(cur.status(), cur.assignee(), List.copyOf(notes));
        return persist(alarmId, next);
    }

    /** 内存 + 库双写：写操作同步落 t_alarm_disposition，重启恢复。 */
    private Disposition persist(String alarmId, Disposition d) {
        String tenant = tenant();
        map.put(key(tenant, alarmId), d);
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

    private static String tenant() {
        String tenant = TenantContext.get();
        return tenant == null || tenant.isBlank() ? "default" : tenant;
    }

    private static String key(String tenant, String alarmId) {
        return tenant + "|" + alarmId;
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
