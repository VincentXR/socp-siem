package com.socp.alert.service;

import com.socp.alert.persistence.entity.DispositionEntity;
import com.socp.alert.persistence.repository.DispositionRepository;


import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.socp.alert.domain.AlarmState;
import com.socp.platform.error.exception.ApiException;
import com.socp.platform.tenant.context.AuthenticatedIdentity;
import com.socp.platform.tenant.context.AuthenticatedIdentityContext;
import com.socp.platform.tenant.context.TenantContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 告警处置（工单化）：状态流转 + 备注 + 分配人 + 操作历史。
 *
 * <p>The database is the sole authority, so every instance observes the same
 * disposition state and a failed write cannot leak into a process-local view.</p>
 *
 * <p>状态机见 {@link AlarmState}：非法跳转返回 409 并列出当前合法目标。
 */
@Service
public class AlarmDispositionService {
    @org.springframework.beans.factory.annotation.Autowired
    private com.socp.platform.auth.security.OperatorDirectory operatorDirectory;

    private void requireAssignee(String assignee) {
        if (operatorDirectory != null && assignee != null && !assignee.isBlank()) {
            operatorDirectory.requireAssignable(assignee.trim());
        }
    }


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

        @com.fasterxml.jackson.annotation.JsonProperty
        public List<String> allowedTransitions() {
            return AlarmState.from(status).map(current -> Arrays.stream(AlarmState.values())
                    .filter(target -> target == current || current.canMoveTo(target))
                    .map(Enum::name).toList()).orElse(List.of());
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
    private final com.socp.alert.persistence.repository.AlarmRepository alarms;

    @org.springframework.beans.factory.annotation.Autowired
    public AlarmDispositionService(DispositionRepository repo,
            com.socp.alert.persistence.repository.AlarmRepository alarms) {
        this.repo = repo;
        this.alarms = alarms;
    }

    /** Focused legacy unit-test constructor; production always resolves the alarm. */
    public AlarmDispositionService(DispositionRepository repo) {
        this(repo, null);
    }

    @Transactional(readOnly = true)
    public Disposition get(String alarmId) {
        return repo.findByAlarmIdAndTenantId(alarmId, tenant())
                .map(AlarmDispositionService::toDisposition)
                .orElseGet(() -> toDisposition(newRow(alarmId, tenant())));
    }

    @Transactional
    public Disposition setStatus(String alarmId, String status) {
        AlarmState target = requireState(status);
        Disposition cur = currentForUpdate(alarmId);
        // Deterministic idempotency: re-applying the current status is a no-op, so a
        // retried or duplicated request neither rewrites the row nor churns updated_at.
        if (target.name().equals(cur.status())) return cur;
        requireLegalMove(cur.status(), target);
        Disposition next = new Disposition(target.name(), cur.assignee(),
                appendHistory(cur, "Status: " + cur.status() + " → " + target.name()), cur.tags());
        return persist(alarmId, next);
    }

    @Transactional
    public Disposition setStatus(String alarmId, String status, String reason, String classification) {
        status = normalizeOptionalStatus(status);
        if (status == null) throw ApiException.badRequest("status is required");
        boolean closing = "RESOLVED".equals(status) || "CLOSED".equals(status);
        requireClosureEvidence(status, reason, classification);
        Disposition current = currentForUpdate(alarmId);
        if (Objects.equals(status, current.status())) return current;
        Disposition updated = setStatus(alarmId, status);
        if (closing) return addNote(alarmId, batchActor(), "Closure [" + classification + "]: " + reason.trim());
        return updated;
    }

    private static List<Disposition.Note> appendHistory(Disposition current, String change) {
        List<Disposition.Note> notes = new ArrayList<>(current.notes());
        notes.add(new Disposition.Note(batchActor(), change, Instant.now()));
        return List.copyOf(notes);
    }

    @Transactional
    public Disposition assign(String alarmId, String assignee) {
        requireAssignee(assignee);
        Disposition cur = currentForUpdate(alarmId);
        String target = assignee == null ? null : assignee.trim();
        Disposition next = new Disposition(cur.status(), target, appendHistory(cur, "Owner: " + Objects.toString(cur.assignee(), "unassigned") + " → " + Objects.toString(target, "unassigned")), cur.tags());
        // Deterministic idempotency: assigning the current owner is a no-op, so SOAR
        // or UI retries of the same assignment do not rewrite the disposition.
        if (Objects.equals(target, cur.assignee())) return cur;
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
     *
     * <p>The recorded reason carries the same durable set-once ledger as
     * connector notes: its identity is derived deterministically from the
     * trusted actor plus the normalized mutation, so an at-least-once retry of
     * the same batch neither duplicates the note nor rewrites a row that already
     * holds the requested state.  Status and assignee are still reconciled on a
     * replay, because unlike a note they are the caller's requested state and may
     * have drifted in between.  A blank reason records no note at all: the
     * independent {@code @AuditOperation} entry is the evidence of the call.</p>
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
        AlarmState targetState = normalizedStatus == null ? null : requireState(normalizedStatus);
        String normalizedAssignee = normalizeOptional(assignee);
        requireAssignee(normalizedAssignee);
        String normalizedReason = normalizeOptional(reason);
        if (normalizedStatus == null && normalizedAssignee == null && normalizedReason == null) {
            throw ApiException.badRequest("at least one of status, assignee or reason is required");
        }
        String actor = batchActor();
        String reasonKey = normalizedReason == null
                ? null : batchReasonKey(actor, normalizedStatus, normalizedAssignee, normalizedReason);

        List<Map<String, Object>> items = new ArrayList<>();
        normalizedIds.stream().sorted(Comparator.naturalOrder()).forEach(alarmId -> {
            DispositionEntity row = lockedRow(alarmId);
            Disposition current = toDisposition(row);
            // The batch is one transaction, so a single illegal move rolls the whole
            // selection back rather than half-applying a triage decision.
            if (targetState != null && !targetState.name().equals(current.status())) {
                requireLegalMove(current.status(), targetState);
            }
            boolean reasonPending = reasonKey != null
                    && !readNoteKeys(row.getNoteKeys()).contains(reasonKey);
            List<Disposition.Note> notes = new ArrayList<>(current.notes());
            if (reasonPending) {
                notes.add(new Disposition.Note(actor, normalizedReason, Instant.now()));
            }
            Disposition next = new Disposition(
                    normalizedStatus == null ? current.status() : normalizedStatus,
                    normalizedAssignee == null ? current.assignee() : normalizedAssignee,
                    List.copyOf(notes), current.tags());
            boolean statePending = !next.status().equals(current.status())
                    || !Objects.equals(next.assignee(), current.assignee());
            // Deterministic idempotency: a replay that adds no note and changes no
            // state leaves the row (and its updated_at) untouched.
            if (reasonPending || statePending) write(row, next, reasonPending ? reasonKey : null);
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

    /** Analyst boundary: connector state reconciliation keeps its separate legacy contract. */
    @Transactional
    public Map<String, Object> batchUpdate(List<String> alarmIds, String status,
            String assignee, String reason, String classification) {
        String normalized = normalizeOptionalStatus(status);
        requireClosureEvidence(normalized, reason, classification);
        String evidence = ("RESOLVED".equals(normalized) || "CLOSED".equals(normalized))
                ? "Closure [" + classification + "]: " + reason.trim() : reason;
        return batchUpdate(alarmIds, normalized, assignee, evidence);
    }

    private static void requireClosureEvidence(String status, String reason, String classification) {
        if (!"RESOLVED".equals(status) && !"CLOSED".equals(status)) return;
        if (reason == null || reason.isBlank() || reason.length() > 4096 || classification == null
                || !Set.of("TRUE_POSITIVE", "FALSE_POSITIVE", "BENIGN", "UNDETERMINED").contains(classification))
            throw ApiException.badRequest("Closure requires a classification and reason/evidence");
    }

    /** Persist the complete disposition in the authoritative tenant row. */
    private Disposition persist(String alarmId, Disposition d) {
        return persist(alarmId, d, null);
    }

    private Disposition persist(String alarmId, Disposition d, String noteKey) {
        DispositionEntity row = managedRow(alarmId);
        if (noteKey != null && readNoteKeys(row.getNoteKeys()).contains(noteKey)) {
            return toDisposition(row);
        }
        return write(row, d, noteKey);
    }

    /** The row behind {@code findByAlarmIdAndTenantId}, or an unsaved new one. */
    private DispositionEntity managedRow(String alarmId) {
        String tenant = tenant();
        return repo.findByAlarmIdAndTenantId(alarmId, tenant).orElseGet(() -> newRow(alarmId, tenant));
    }

    /** The row locked for update, or an unsaved new one. */
    private DispositionEntity lockedRow(String alarmId) {
        String tenant = tenant();
        // Lock the durable parent even before a disposition exists. This fences
        // first-note/assign/status races across replicas without a second transaction.
        if (alarms != null) alarms.findForDispositionUpdate(tenant, alarmId)
                .orElseThrow(() -> ApiException.notFound("Alarm does not exist: " + alarmId));
        return repo.findForUpdate(alarmId, tenant).orElseGet(() -> newRow(alarmId, tenant));
    }

    private DispositionEntity newRow(String alarmId, String tenant) {
        DispositionEntity created = new DispositionEntity();
        created.setAlarmId(alarmId);
        created.setTenantId(tenant);
        if (alarms != null) {
            var alarm = alarms.findByTenantIdAndId(tenant, alarmId)
                    .orElseThrow(() -> ApiException.notFound("Alarm does not exist: " + alarmId));
            created.setStatus(alarm.getStatus() == null ? "OPEN" : alarm.getStatus());
        }
        return created;
    }

    /**
     * Writes the disposition onto an already loaded row.  A non-null note key is
     * recorded in the bounded set-once ledger alongside the note it authorizes.
     */
    private Disposition write(DispositionEntity row, Disposition d, String noteKey) {
        row.setStatus(d.status());
        row.setAssignee(d.assignee());
        row.setNotes(writeNotes(d.notes()));
        row.setTags(writeTags(d.tags()));
        if (noteKey != null) row.setNoteKeys(writeNoteKeys(row.getNoteKeys(), noteKey));
        repo.save(row);
        return d;
    }

    private Disposition currentForUpdate(String alarmId) {
        return toDisposition(lockedRow(alarmId));
    }

    /**
     * Author of a batch triage note, taken from the authenticated principal rather
     * than from anything the caller can set — the same trust rule the single-note
     * endpoint applies through {@code DispositionActor}.  The batch request carries
     * no actor field, so every identity is recorded by its own subject; the
     * {@code @AuditOperation} entry stays the independent evidence of the call.
     */
    private static String batchActor() {
        return AuthenticatedIdentityContext.current()
                .map(AuthenticatedIdentity::subject)
                .orElse("operator");
    }

    /**
     * Deterministic identity of one batch triage reason: the same actor re-applying
     * the same normalized mutation replays onto the same key, which the row's
     * note-key ledger accepts at most once.  It is a digest because a reason is free
     * text up to 4KB while the ledger column has to stay bounded.
     */
    private static String batchReasonKey(String actor, String status, String assignee, String reason) {
        String canonical = "actor=" + actor + "\nstatus=" + status
                + "\nassignee=" + assignee + "\nreason=" + reason;
        // SHA-256 is mandated by the JCA specification, so this guard can only fail on a
        // JVM that does not implement it; it must not silently weaken the recorded key.
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8));
            return "batch:" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 is required", impossible); }
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
        return normalized == null ? null : requireState(normalized).name();
    }

    /** Reject an unknown status vocabulary before any row is locked. */
    private static AlarmState requireState(String status) {
        return AlarmState.from(status).orElseThrow(() -> ApiException.badRequest(
                "非法状态: " + status + "（可选 " + String.join("/", AlarmState.PATTERN.split("\\|")) + "）"));
    }

    /**
     * The stored state is authoritative, so an illegal move is reported as a
     * conflict rather than a format error and the analyst is told the legal targets.
     */
    private static void requireLegalMove(String current, AlarmState target) {
        AlarmState from = AlarmState.from(current).orElse(AlarmState.OPEN);
        if (!from.canMoveTo(target)) {
            List<String> legal = Arrays.stream(AlarmState.values())
                    .filter(from::canMoveTo).map(Enum::name).toList();
            throw ApiException.conflict("状态不能从 " + from.name() + " 变更为 " + target.name()
                    + "（当前可选: " + String.join("/", legal) + "）");
        }
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
