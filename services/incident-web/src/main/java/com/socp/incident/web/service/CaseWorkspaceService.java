package com.socp.incident.web.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.socp.incident.web.api.request.CaseChangeRequest;
import com.socp.incident.web.api.request.CaseNoteRequest;
import com.socp.incident.web.domain.Case;
import com.socp.incident.web.domain.CaseState;
import com.socp.incident.web.domain.TimelineEvent;
import com.socp.incident.web.persistence.entity.CaseMutationEntity;
import com.socp.incident.web.persistence.repository.CaseMutationRepository;
import com.socp.incident.web.persistence.repository.CaseRepository;
import com.socp.incident.web.persistence.store.CaseStore;
import com.socp.platform.error.exception.ApiException;
import com.socp.platform.tenant.context.TenantContext;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Row-locked durable commands; receipts, metadata and visible history commit together. */
@Service
public class CaseWorkspaceService {
    @org.springframework.beans.factory.annotation.Autowired
    private com.socp.platform.auth.security.OperatorDirectory operatorDirectory;

    private void requireAssignee(String assignee) {
        if (operatorDirectory != null && assignee != null && !assignee.isBlank()) {
            operatorDirectory.requireAssignable(assignee.trim());
        }
    }

    private static final Set<String> CLASSIFICATIONS = Set.of("TRUE_POSITIVE", "FALSE_POSITIVE", "BENIGN", "INCONCLUSIVE");
    private final CaseStore store;
    private final CaseRepository cases;
    private final CaseMutationRepository receipts;
    private final EntityManager entityManager;
    private final ObjectMapper json;

    public CaseWorkspaceService(CaseStore store, CaseRepository cases, CaseMutationRepository receipts,
                                EntityManager entityManager, ObjectMapper json) {
        this.store = store; this.cases = cases; this.receipts = receipts;
        this.entityManager = entityManager; this.json = json;
    }

    @Transactional
    public Map<String, Object> change(String id, String actor, CaseChangeRequest request) {
        bounded(request.assignee(), 255); bounded(request.classification(), 32);
        bounded(request.result(), 2000); bounded(request.reason(), 4000);
        bounded(request.evidence(), 8000); bounded(request.remainingActions(), 4000);
        Case current = lock(id);
        String fingerprint = fingerprint(actor, "change", request);
        if (replayed(id, request.idempotencyKey(), fingerprint)) return response(current, true, false);
        requireAssignee(request.assignee());
        CaseState target = CaseState.from(request.status())
                .orElseThrow(() -> ApiException.badRequest("Invalid case status"));
        requireVersion(current, request.expectedVersion());
        CaseState from = CaseState.from(current.status()).orElse(CaseState.OPEN);
        if (from != target && !from.canMoveTo(target)) {
            throw ApiException.conflict("案件状态不能从 " + from + " 变更为 " + target);
        }
        String status = target.name();
        String assignee = request.assignee() == null ? current.assignee() : nullable(request.assignee());
        boolean changed = !Objects.equals(current.status(), status) || !Objects.equals(current.assignee(), assignee);
        boolean closing = !current.status().equals(status) && Set.of("RESOLVED", "CLOSED").contains(status);
        if (closing && (!CLASSIFICATIONS.contains(text(request.classification())) || text(request.result()).isEmpty()
                || text(request.reason()).isEmpty() || text(request.evidence()).isEmpty() || text(request.remainingActions()).isEmpty())) {
            throw ApiException.badRequest("Closure requires classification, result, reason, evidence and remaining actions");
        }
        if (changed) {
            store.saveMetadata(current.withStatus(status, assignee));
            String message = actor + ": " + current.status() + " → " + status
                    + "\nAssignee: " + text(current.assignee()) + " → " + text(assignee);
            if (closing) message += "\nClassification: " + request.classification() + "\nResult: " + request.result().trim()
                    + "\nReason: " + request.reason().trim() + "\nEvidence: " + request.evidence().trim()
                    + "\nRemaining actions: " + request.remainingActions().trim();
            store.appendTimeline(id, new TimelineEvent(Instant.now(), closing ? "CLOSURE" : "STATUS",
                    message, "analyst", null, "change:" + request.idempotencyKey()));
        }
        remember(id, request.idempotencyKey(), fingerprint);
        return response(refresh(id), false, changed);
    }

    @Transactional
    public Map<String, Object> assign(String id, String actor, String assignee, Long version, String key) {
        bounded(assignee, 255);
        Case current = lock(id);
        String fingerprint = fingerprint(actor, "assign", java.util.Arrays.asList(assignee, version));
        if (replayed(id, key, fingerprint)) return response(current, true, false);
        requireVersion(current, version);
        requireAssignee(assignee);
        String next = nullable(assignee);
        boolean changed = !Objects.equals(current.assignee(), next);
        if (changed) {
            store.saveMetadata(current.withStatus(current.status(), next));
            store.appendTimeline(id, new TimelineEvent(Instant.now(), "ASSIGN", actor + ": "
                    + text(current.assignee()) + " → " + text(next), "analyst", null, "assign:" + key));
        }
        remember(id, key, fingerprint);
        return response(refresh(id), false, changed);
    }

    @Transactional
    public Map<String, Object> claim(String id, String actor, long expectedVersion, String key) {
        Case current = lock(id);
        String fingerprint = fingerprint(actor, "claim", expectedVersion);
        if (replayed(id, key, fingerprint)) return response(current, true, false);
        requireVersion(current, expectedVersion);
        requireAssignee(actor);
        if (current.assignee() != null && !current.assignee().isBlank() && !current.assignee().equals(actor)) {
            throw ApiException.of(409, "Case is already assigned; refresh before changing ownership");
        }
        boolean changed = !actor.equals(current.assignee());
        if (changed) {
            store.saveMetadata(current.withStatus(current.status(), actor));
            store.appendTimeline(id, new TimelineEvent(Instant.now(), "ASSIGN", actor + ": claimed case",
                    "analyst", null, "claim:" + key));
        }
        remember(id, key, fingerprint);
        return response(refresh(id), false, changed);
    }

    @Transactional
    public Map<String, Object> note(String id, String actor, CaseNoteRequest request) {
        Case current = lock(id);
        String fingerprint = fingerprint(actor, "note", request.content());
        if (replayed(id, request.idempotencyKey(), fingerprint)) return response(current, true, false);
        if (text(request.content()).isEmpty()) throw ApiException.badRequest("Note must not be blank");
        // Keep the legacy event identity across client/API upgrades. A remotely
        // committed legacy note may predate its caller's completion acknowledgement
        // and has no workspace receipt yet. Adopt it only when the payload matches.
        String eventKey = "note:" + request.idempotencyKey().trim();
        var previous = store.timelineEvent(id, eventKey);
        if (previous.isPresent()) {
            requireSameNote(previous.get(), actor, request.content());
            remember(id, request.idempotencyKey(), fingerprint);
            return response(current, true, false);
        }
        boolean appended = store.appendTimeline(id, new TimelineEvent(Instant.now(), "NOTE", actor + ": " + request.content().trim(),
                "analyst", null, eventKey));
        if (!appended) {
            // A legacy writer may have committed after the lookup. The durable
            // unique key still prevents a second note; verify its winner's payload.
            requireSameNote(store.timelineEvent(id, eventKey)
                    .orElseThrow(() -> ApiException.of(409, "Note changed; refresh before retrying")),
                    actor, request.content());
        }
        remember(id, request.idempotencyKey(), fingerprint);
        return response(refresh(id), !appended, appended);
    }

    private static void requireSameNote(TimelineEvent previous, String actor, String content) {
        if (!"NOTE".equals(previous.type()) || (!Objects.equals(previous.message(), actor + ": " + content)
                && !Objects.equals(previous.message(), actor + ": " + content.trim()))) {
            throw ApiException.of(409, "Idempotency key was used for a different command");
        }
    }

    private Case lock(String id) {
        cases.lockByTenantIdAndId(TenantContext.require(), id).orElseThrow(() -> ApiException.notFound("Case not found"));
        return store.getMetadata(id);
    }

    private Case refresh(String id) {
        entityManager.flush();
        // appendTimeline increments the version with a bulk update; never return stale managed metadata.
        entityManager.clear();
        return store.getMetadata(id);
    }

    private boolean replayed(String id, String key, String fingerprint) {
        if (key == null || key.isBlank() || key.length() > 128) throw ApiException.badRequest("A bounded idempotency key is required");
        return receipts.findByTenantIdAndCaseIdAndRequestKey(TenantContext.require(), id, key).map(receipt -> {
            if (!receipt.getFingerprint().equals(fingerprint)) throw ApiException.of(409, "Idempotency key was used for a different command");
            return true;
        }).orElse(false);
    }

    private void remember(String id, String key, String fingerprint) {
        receipts.save(new CaseMutationEntity(TenantContext.require(), id, key, fingerprint));
    }

    private String fingerprint(String actor, String operation, Object payload) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(json.writeValueAsString(java.util.List.of(actor, operation, payload)).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception failure) { throw new IllegalStateException("Unable to fingerprint case command", failure); }
    }

    private static void requireVersion(Case current, Long version) {
        if (version == null || version < 0) throw ApiException.badRequest("expectedVersion is required");
        if (current.rowVersion() != version) throw ApiException.of(409, "Case changed; refresh and review before saving");
    }
    private static void bounded(String value, int maximum) {
        if (value != null && value.length() > maximum) throw ApiException.badRequest("Case command field exceeds its maximum length");
    }
    private static String nullable(String value) { return text(value).isEmpty() ? null : value.trim(); }
    private static String text(String value) { return value == null ? "" : value.trim(); }
    private static Map<String, Object> response(Case incident, boolean duplicate, boolean changed) {
        return Map.of("case", incident, "duplicate", duplicate, "changed", changed);
    }
}
