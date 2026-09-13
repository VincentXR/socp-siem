package com.socp.soar.web.service;

import com.socp.platform.tenant.context.AuthenticatedIdentityContext;
import com.socp.soar.web.domain.SoarRunStatus;
import com.socp.soar.web.persistence.entity.PlaybookVersionEntity;
import com.socp.soar.web.persistence.entity.SoarApprovalDecisionEntity;
import com.socp.soar.web.persistence.entity.SoarApprovalEntity;
import com.socp.soar.web.persistence.entity.SoarDispatchOutboxEntity;
import com.socp.soar.web.persistence.entity.SoarRunEntity;
import com.socp.soar.web.persistence.repository.PlaybookVersionRepository;
import com.socp.soar.web.persistence.repository.SoarApprovalDecisionRepository;
import com.socp.soar.web.persistence.repository.SoarApprovalRepository;
import com.socp.soar.web.persistence.repository.SoarDispatchOutboxRepository;
import com.socp.soar.web.persistence.repository.SoarRunRepository;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Approval state transitions extracted from {@link SoarService}.  The
 * service facade keeps the transaction and audit annotations while this
 * collaborator owns quorum, policy, expiry and workflow signalling rules.
 */
final class SoarApprovalCommandService {

    private final SoarService service;
    private final SoarApprovalRepository approvals;
    private SoarApprovalDecisionRepository approvalDecisions;
    private final SoarRunRepository runs;
    private final SoarDispatchOutboxRepository dispatches;
    private final PlaybookVersionRepository versions;

    SoarApprovalCommandService(SoarService service) {
        this.service = service;
        this.approvals = service.approvals;
        this.approvalDecisions = service.approvalDecisions;
        this.runs = service.runs;
        this.dispatches = service.dispatches;
        this.versions = service.versions;
    }

    void setApprovalDecisions(SoarApprovalDecisionRepository approvalDecisions) {
        this.approvalDecisions = approvalDecisions;
    }

    private String tenant() { return service.tenant(); }

    private static String actor() { return SoarService.actor(); }

    private static String required(String value, String field, int max) {
        return SoarService.required(value, field, max);
    }

    private static String redactFreeText(String value, int max) {
        return SoarService.redactFreeText(value, max);
    }

    private static String limit(String value, int max) {
        return SoarService.limit(value, max);
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }

    private static ResponseStatusException error(HttpStatus status, String code, String message) {
        return SoarService.error(status, code, message);
    }

    private boolean terminalRunProjection(SoarRunEntity run) {
        return SoarService.terminalRunProjection(run);
    }

    private Map<String, Object> approvalView(SoarApprovalEntity approval) {
        return service.approvalView(approval);
    }

    private void appendEvent(String runId, String type, String actor, String summary,
                             Map<String, Object> detail) {
        service.appendEvent(runId, type, actor, summary, detail);
    }

    private void enqueueSignal(SoarRunEntity run, String type, Map<String, Object> payload) {
        service.enqueueSignal(run, type, payload);
    }

    Map<String, Object> decideApproval(String id, boolean approve, String decisionReason) {
        String tenant = tenant();
        SoarApprovalEntity approval = approvals.findByTenantIdAndIdForUpdate(tenant, id)
                .or(() -> approvals.findByTenantIdAndId(tenant, id))
                .orElseThrow(() -> error(HttpStatus.NOT_FOUND, "SOAR_APPROVAL_NOT_FOUND", "approval not found"));
        SoarRunEntity run = runs.findByTenantIdAndIdForUpdate(tenant, approval.getRunId())
                .or(() -> runs.findByTenantIdAndId(tenant, approval.getRunId()))
                .orElseThrow(() -> error(HttpStatus.NOT_FOUND, "SOAR_RUN_NOT_FOUND", "approval run not found"));
        if (terminalRunProjection(run)) {
            throw error(HttpStatus.CONFLICT, "SOAR_RUN_NOT_RESUMABLE",
                    "the approval run is already terminal");
        }
        if (SoarRunStatus.CANCELLING.name().equals(run.getStatus())) {
            throw error(HttpStatus.CONFLICT, "SOAR_RUN_NOT_RESUMABLE",
                    "the approval run is cancelling");
        }
        if (!"PENDING".equals(approval.getStatus())) {
            throw error(HttpStatus.CONFLICT, "SOAR_APPROVAL_ALREADY_DECIDED", "approval is already decided");
        }
        Instant now = Instant.now();
        if (approval.getExpiresAt() != null && approval.getExpiresAt().isBefore(now)) {
            expireApprovalLocked(approval, run, now);
            throw error(HttpStatus.CONFLICT, "SOAR_APPROVAL_EXPIRED", "approval has expired");
        }
        String approver = actor();
        String runRequester = run.getRequestedBy();
        boolean sameAsRunRequester = approver != null && runRequester != null
                && approver.equalsIgnoreCase(runRequester);
        boolean sameAsApprovalRequester = approver != null && approval.getRequestedBy() != null
                && !"workflow".equalsIgnoreCase(approval.getRequestedBy())
                && approver.equalsIgnoreCase(approval.getRequestedBy());
        boolean sameAsRecentEditor = false;
        if (versions != null && approver != null && run.getPlaybookVersionId() != null) {
            Optional<PlaybookVersionEntity> sourceVersion = versions.findByTenantIdAndId(
                    tenant, run.getPlaybookVersionId());
            if (sourceVersion != null && sourceVersion.isPresent()) {
                String editor = sourceVersion.get().getCreatedBy();
                sameAsRecentEditor = editor != null && approver.equalsIgnoreCase(editor);
            }
        }
        if (sameAsRunRequester || sameAsApprovalRequester || sameAsRecentEditor) {
            throw error(HttpStatus.FORBIDDEN, "SOAR_SELF_APPROVAL_DENIED",
                    "the requester or recent playbook editor cannot approve their own high-risk run");
        }
        if (!approvalPolicyAllows(approval)) {
            throw error(HttpStatus.FORBIDDEN, "SOAR_APPROVER_POLICY_FORBIDDEN",
                    "the current operator is not in the approval policy role/group allow-list");
        }
        String safeDecisionReason = redactFreeText(required(decisionReason, "decisionReason", 2048), 2048);
        int requiredApprovals = Math.max(1, approval.getRequiredApprovals());
        if (requiredApprovals > 1 && approvalDecisions == null) {
            // Never silently downgrade a multi-vote policy when the durable
            // decision projection is unavailable.
            throw error(HttpStatus.SERVICE_UNAVAILABLE, "SOAR_APPROVAL_DECISION_STORE_UNAVAILABLE",
                    "multi-approval decision store is unavailable");
        }
        if (approvalDecisions != null) {
            Optional<SoarApprovalDecisionEntity> priorVote =
                    approvalDecisions.findByTenantIdAndApprovalIdAndActorId(tenant, approval.getId(), approver);
            if (priorVote != null && priorVote.isPresent()) {
                // A retried browser request from the same approver is idempotent.
                return approvalView(approval);
            }
        }
        recordApprovalDecision(tenant, approval.getId(), approver,
                approve ? "APPROVE" : "REJECT", safeDecisionReason, now);
        int approvedVotes = approvalDecisions == null
                ? (approve ? 1 : 0)
                : countApprovedVotes(tenant, approval.getId());
        if (approve && approvedVotes < requiredApprovals) {
            approval.setApprover(approver);
            approval.setDecisionReason(safeDecisionReason);
            approvals.save(approval);
            appendEvent(run.getId(), "APPROVAL_VOTE_RECORDED", approver,
                    "Approval vote recorded; quorum not reached",
                    Map.of("approvalId", id, "approvedVotes", approvedVotes,
                            "requiredApprovals", requiredApprovals));
            run.setUpdatedAt(now);
            runs.save(run);
            return approvalView(approval);
        }
        approval.setStatus(approve ? "APPROVED" : "REJECTED");
        approval.setApprover(approver);
        approval.setDecisionReason(safeDecisionReason);
        approval.setDecidedAt(now);
        approvals.save(approval);

        SoarDispatchOutboxEntity outbox = dispatches.findByTenantIdAndRunId(tenant, run.getId()).orElse(null);
        boolean attachedWorkflow = run.getTemporalWorkflowId() != null
                && !run.getTemporalWorkflowId().isBlank();
        if (attachedWorkflow) {
            appendEvent(run.getId(), approve ? "APPROVAL_GRANTED" : "APPROVAL_REJECTED", approver,
                    approve ? "Approval granted; workflow signal queued" : "Approval rejected; workflow signal queued",
                    Map.of("approvalId", id, "approvedVotes", approvedVotes,
                            "requiredApprovals", requiredApprovals));
        } else if (approve) {
            run.setStatus(SoarRunStatus.QUEUED.name());
            if (outbox != null) {
                outbox.setStatus("PENDING");
                outbox.setNextAttemptAt(now);
                outbox.setUpdatedAt(now);
                dispatches.save(outbox);
            }
            appendEvent(run.getId(), "APPROVAL_GRANTED", approver, "Approval granted",
                    Map.of("approvalId", id, "approvedVotes", approvedVotes,
                            "requiredApprovals", requiredApprovals));
        } else {
            run.setStatus(SoarRunStatus.SUPPRESSED.name());
            run.setCompletedAt(now);
            if (outbox != null) {
                outbox.setStatus("CANCELLED");
                outbox.setUpdatedAt(now);
                dispatches.save(outbox);
            }
            appendEvent(run.getId(), "APPROVAL_REJECTED", approver, "Approval rejected",
                    Map.of("approvalId", id, "approvedVotes", approvedVotes,
                            "requiredApprovals", requiredApprovals));
        }
        run.setUpdatedAt(now);
        runs.save(run);
        if (attachedWorkflow) {
            enqueueSignal(run, "APPROVAL", Map.of("approve", approve, "approvalId", id,
                    "approvalKey", nullSafe(approval.getApprovalKey())));
        }
        return approvalView(approval);
    }

    boolean expireApproval(String id, Instant now) {
        String tenant = tenant();
        Instant at = now == null ? Instant.now() : now;
        Optional<SoarApprovalEntity> locked = approvals.findByTenantIdAndIdForUpdate(tenant, id);
        if (locked == null) locked = approvals.findByTenantIdAndId(tenant, id);
        if (locked == null || locked.isEmpty()) return false;
        SoarApprovalEntity row = locked.get();
        if (!"PENDING".equals(row.getStatus()) || row.getExpiresAt() == null
                || row.getExpiresAt().isAfter(at)) return false;
        Optional<SoarRunEntity> run = runs.findByTenantIdAndIdForUpdate(tenant, row.getRunId());
        if (run == null) run = runs.findByTenantIdAndId(tenant, row.getRunId());
        if (run == null || run.isEmpty()) return false;
        if (terminalRunProjection(run.get()) || SoarRunStatus.CANCELLING.name().equals(run.get().getStatus())) {
            return false;
        }
        expireApprovalLocked(row, run.get(), at);
        return true;
    }

    private void expireApprovalLocked(SoarApprovalEntity approval, SoarRunEntity run, Instant now) {
        if (!"PENDING".equals(approval.getStatus())) return;
        approval.setStatus("EXPIRED");
        approval.setDecidedAt(now);
        approval.setDecisionReason("approval expired by system");
        recordApprovalDecision(approval.getTenantId(), approval.getId(), "system",
                "EXPIRE", "approval expired by system", now);
        approvals.save(approval);
        boolean attachedWorkflow = run.getTemporalWorkflowId() != null
                && !run.getTemporalWorkflowId().isBlank();
        if (SoarRunStatus.WAITING_APPROVAL.name().equals(run.getStatus()) && attachedWorkflow) {
            enqueueSignal(run, "APPROVAL", Map.of("approve", false,
                    "approvalId", approval.getId(), "approvalKey", nullSafe(approval.getApprovalKey()),
                    "expired", true));
            appendEvent(run.getId(), "APPROVAL_EXPIRED", "system",
                    "Approval expired; workflow signal queued", Map.of("approvalId", approval.getId()));
        } else if (SoarRunStatus.WAITING_APPROVAL.name().equals(run.getStatus())) {
            run.setStatus(SoarRunStatus.SUPPRESSED.name());
            run.setErrorCode("APPROVAL_EXPIRED");
            run.setErrorMessage("approval expired before a decision was recorded");
            run.setCompletedAt(now);
            run.setUpdatedAt(now);
            runs.save(run);
            dispatches.findByTenantIdAndRunId(run.getTenantId(), run.getId()).ifPresent(outbox -> {
                if (!"DISPATCHED".equals(outbox.getStatus())) {
                    outbox.setStatus("CANCELLED");
                    outbox.setUpdatedAt(now);
                    dispatches.save(outbox);
                }
            });
            appendEvent(run.getId(), "APPROVAL_EXPIRED", "system",
                    "Approval expired; run suppressed", Map.of("approvalId", approval.getId()));
        }
    }

    private int countApprovedVotes(String tenant, String approvalId) {
        if (approvalDecisions == null) return 0;
        List<SoarApprovalDecisionEntity> rows = approvalDecisions
                .findByTenantIdAndApprovalIdOrderByCreatedAtAsc(tenant, approvalId);
        if (rows == null) return 0;
        return (int) rows.stream().filter(vote -> "APPROVE".equalsIgnoreCase(vote.getDecision())).count();
    }

    private void recordApprovalDecision(String tenant, String approvalId, String actor,
                                        String decision, String reason, Instant createdAt) {
        if (approvalDecisions == null) return;
        SoarApprovalDecisionEntity vote = new SoarApprovalDecisionEntity();
        vote.setId(UUID.randomUUID().toString());
        vote.setTenantId(tenant);
        vote.setApprovalId(approvalId);
        vote.setActorId(limit(actor == null ? "operator" : actor, 128));
        vote.setDecision(decision);
        vote.setReason(redactFreeText(reason, 2048));
        vote.setCreatedAt(createdAt == null ? Instant.now() : createdAt);
        approvalDecisions.save(vote);
    }

    private boolean approvalPolicyAllows(SoarApprovalEntity approval) {
        var policy = service.readTree(approval == null ? null : approval.getPolicyJson());
        if (!policy.isObject()) return true;
        Set<String> roles = policyPrincipals(policy, "allowedRoles", "approverRoles");
        Set<String> groups = policyPrincipals(policy, "allowedGroups", "approverGroups");
        if (roles.isEmpty() && groups.isEmpty()) return true;
        Set<String> authorities = securityAuthorities();
        if (authorities.isEmpty()) return false;
        return matchesPrincipal(authorities, roles, false)
                || matchesPrincipal(authorities, groups, true);
    }

    private static Set<String> policyPrincipals(com.fasterxml.jackson.databind.JsonNode policy,
                                                String canonical, String alias) {
        com.fasterxml.jackson.databind.JsonNode values = policy.path(canonical).isArray()
                ? policy.path(canonical) : policy.path(alias);
        Set<String> result = new LinkedHashSet<>();
        if (values == null || !values.isArray()) return result;
        for (com.fasterxml.jackson.databind.JsonNode value : values) {
            if (value != null && value.isTextual() && !value.asText().isBlank()) {
                result.add(value.asText().trim().toUpperCase(Locale.ROOT));
            }
        }
        return result;
    }

    private static boolean matchesPrincipal(Set<String> authorities, Set<String> expected, boolean group) {
        if (expected.isEmpty()) return false;
        for (String wantedRaw : expected) {
            String wanted = principalWithoutPrefix(wantedRaw, group ? "GROUP_" : "ROLE_");
            boolean explicitlyOtherKind = group ? wantedRaw.startsWith("ROLE_") : wantedRaw.startsWith("GROUP_");
            if (explicitlyOtherKind) continue;
            for (String actualRaw : authorities) {
                String actual = actualRaw.toUpperCase(Locale.ROOT);
                if (group && actual.startsWith("ROLE_")) continue;
                if (!group && actual.startsWith("GROUP_")) continue;
                if (principalWithoutPrefix(actual, group ? "GROUP_" : "ROLE_").equals(wanted)) return true;
            }
        }
        return false;
    }

    private static String principalWithoutPrefix(String value, String prefix) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        return normalized.startsWith(prefix) ? normalized.substring(prefix.length()) : normalized;
    }

    private static Set<String> securityAuthorities() {
        return AuthenticatedIdentityContext.current()
                .map(identity -> {
                    Set<String> result = new LinkedHashSet<>();
                    for (String authority : identity.authorities()) {
                        result.add(authority.toUpperCase(Locale.ROOT));
                    }
                    return result;
                })
                .orElseGet(Set::of);
    }
}
