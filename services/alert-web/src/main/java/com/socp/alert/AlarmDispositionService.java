package com.socp.alert;

import com.socp.platform.error.ApiException;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 告警处置（工单化）：状态流转 + 备注 + 分配人 + 操作历史。
 * 集群无关实现（内存）；生产替换为 PG t_alarm_hist（审计留存），接口不变。
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

    private final Map<String, Disposition> map = new ConcurrentHashMap<>();

    public Disposition get(String alarmId) {
        return map.getOrDefault(alarmId, new Disposition("OPEN", null, List.of()));
    }

    public Disposition setStatus(String alarmId, String status) {
        String s = status == null ? "" : status.trim().toUpperCase();
        if (!List.of("OPEN", "INVESTIGATING", "RESOLVED", "CLOSED").contains(s)) {
            throw ApiException.badRequest("非法状态: " + status + "（可选 OPEN/INVESTIGATING/RESOLVED/CLOSED）");
        }
        Disposition cur = get(alarmId);
        Disposition next = new Disposition(s, cur.assignee(), cur.notes());
        map.put(alarmId, next);
        return next;
    }

    public Disposition assign(String alarmId, String assignee) {
        Disposition cur = get(alarmId);
        Disposition next = new Disposition(cur.status(), assignee, cur.notes());
        map.put(alarmId, next);
        return next;
    }

    public Disposition addNote(String alarmId, String author, String content) {
        if (content == null || content.isBlank()) {
            throw ApiException.badRequest("备注内容不能为空");
        }
        Disposition cur = get(alarmId);
        List<Disposition.Note> notes = new ArrayList<>(cur.notes());
        notes.add(new Disposition.Note(author == null ? "operator" : author, content.trim(), Instant.now()));
        Disposition next = new Disposition(cur.status(), cur.assignee(), List.copyOf(notes));
        map.put(alarmId, next);
        return next;
    }
}
