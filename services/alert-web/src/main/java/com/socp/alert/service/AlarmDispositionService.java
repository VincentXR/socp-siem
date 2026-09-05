package com.socp.alert.service;

import com.socp.alert.persistence.entity.DispositionEntity;
import com.socp.alert.persistence.repository.DispositionRepository;


import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
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
            List<Note> notes,
            List<String> tags
    ) {
        /** Compatibility constructor for callers that predate disposition tags. */
        public Disposition(String status, String assignee, List<Note> notes) {
            this(status, assignee, notes, List.of());
        }

        public record Note(String author, String content, Instant at) {
        }
    }

    /**
     * Disposition notes are persisted as JSON and include an Instant.  Use the
     * same Java-time module as the HTTP ObjectMapper; a bare mapper silently
     * fell back to an empty list when serialization failed, which made a
     * successful SOAR note appear to disappear after the request.
     */
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
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
        return addNote(alarmId, author, content, null);
    }

    /** Add a note with durable set-once semantics for connector retries. */
    @Transactional
    public Disposition addNote(String alarmId, String author, String content, String idempotencyKey) {
        if (content == null || content.isBlank()) {
            throw ApiException.badRequest("备注内容不能为空");
        }
        Disposition cur = currentForUpdate(alarmId);
        List<Disposition.Note> notes = new ArrayList<>(cur.notes());
        notes.add(new Disposition.Note(author == null ? "operator" : author, content.trim(), Instant.now()));
        Disposition next = new Disposition(cur.status(), cur.assignee(), List.copyOf(notes), cur.tags());
        return persist(alarmId, next, normalizeIdempotencyKey(idempotencyKey));
    }

    /** Add a tag with set semantics so connector retries cannot duplicate it. */
    @Transactional
    public Disposition addTag(String alarmId, String tag) {
        if (tag == null || tag.isBlank() || tag.trim().length() > 64) {
            throw ApiException.badRequest("标签不能为空且长度不能超过 64");
        }
        Disposition cur = currentForUpdate(alarmId);
        List<String> tags = new ArrayList<>(cur.tags());
        String normalized = tag.trim();
        if (tags.stream().noneMatch(item -> item.equalsIgnoreCase(normalized))) tags.add(normalized);
        return persist(alarmId, new Disposition(cur.status(), cur.assignee(), cur.notes(), List.copyOf(tags)));
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
                    List.copyOf(notes), current.tags());
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
        return persist(alarmId, d, null);
    }

    private Disposition persist(String alarmId, Disposition d, String noteKey) {
        String tenant = tenant();
        DispositionEntity e = repo.findByAlarmIdAndTenantId(alarmId, tenant).orElseGet(() -> {
            DispositionEntity n = new DispositionEntity();
            n.setAlarmId(alarmId);
            n.setTenantId(tenant);
            return n;
        });
        if (noteKey != null && readNoteKeys(e.getNoteKeys()).contains(noteKey)) {
            return toDisposition(e);
        }
        e.setStatus(d.status());
        e.setAssignee(d.assignee());
        e.setNotes(writeNotes(d.notes()));
        e.setTags(writeTags(d.tags()));
        if (noteKey != null) e.setNoteKeys(writeNoteKeys(e.getNoteKeys(), noteKey));
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
                readNotes(e.getNotes()),
                readTags(e.getTags()));
    }

    private static String writeNotes(List<Disposition.Note> notes) {
        try {
            return MAPPER.writeValueAsString(notes);
        } catch (Exception ex) {
            return "[]";
        }
    }

    private static String writeTags(List<String> tags) {
        try {
            return MAPPER.writeValueAsString(tags == null ? List.of() : tags);
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

    private static List<String> readTags(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            List<String> values = MAPPER.readValue(json, new TypeReference<List<String>>() { });
            return values == null ? List.of() : values.stream().filter(value -> value != null && !value.isBlank())
                    .map(String::trim).distinct().toList();
        } catch (Exception ex) {
            return List.of();
        }
    }

    private static String normalizeIdempotencyKey(String value) {
        if (value == null || value.isBlank()) return null;
        String key = value.trim();
        if (key.length() > 255) throw ApiException.badRequest("Idempotency-Key 长度不能超过 255");
        return key;
    }

    private static String writeNoteKeys(String json, String key) {
        LinkedHashSet<String> keys = new LinkedHashSet<>(readNoteKeys(json));
        keys.add(key);
        // Keep the JSON column bounded while retaining the most recent keys.
        while (keys.size() > 2048) keys.remove(keys.iterator().next());
        try { return MAPPER.writeValueAsString(keys); }
        catch (Exception ignored) { return "[" + quote(key) + "]"; }
    }

    private static List<String> readNoteKeys(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            List<String> values = MAPPER.readValue(json, new TypeReference<List<String>>() { });
            return values == null ? List.of() : values.stream().filter(value -> value != null && !value.isBlank()).toList();
        } catch (Exception ignored) { return List.of(); }
    }

    private static String quote(String value) {
        try { return MAPPER.writeValueAsString(value); }
        catch (Exception ignored) { return "\"key\""; }
    }
}
