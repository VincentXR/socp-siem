package com.socp.soar.web.service;

import com.socp.platform.tenant.context.TenantContext;
import com.socp.soar.web.domain.SoarRunStatus;
import com.socp.soar.web.persistence.entity.PlaybookVersionEntity;
import com.socp.soar.web.persistence.entity.SoarNodeRunEntity;
import com.socp.soar.web.persistence.entity.SoarPlaybookEntity;
import com.socp.soar.web.persistence.entity.SoarApprovalEntity;
import com.socp.soar.web.persistence.entity.SoarManualTaskEntity;
import com.socp.soar.web.persistence.entity.SoarSignalOutboxEntity;
import com.socp.soar.web.persistence.repository.PlaybookVersionRepository;
import com.socp.soar.web.persistence.repository.SoarDispatchOutboxRepository;
import com.socp.soar.web.persistence.repository.SoarNodeRunRepository;
import com.socp.soar.web.persistence.repository.SoarPlaybookRepository;
import com.socp.soar.web.persistence.repository.SoarRunEventRepository;
import com.socp.soar.web.persistence.repository.SoarRunRepository;
import com.socp.soar.web.persistence.repository.SoarApprovalRepository;
import com.socp.soar.web.persistence.repository.SoarApprovalDecisionRepository;
import com.socp.soar.web.persistence.repository.SoarActionAttemptRepository;
import com.socp.soar.web.persistence.repository.SoarManualTaskRepository;
import com.socp.soar.web.persistence.repository.SoarSignalOutboxRepository;
import com.socp.soar.web.persistence.repository.SoarArtifactRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import com.socp.soar.web.persistence.entity.SoarDispatchOutboxEntity;
import java.util.Optional;

/** Tenant-scoped read queries and projection selection for {@link SoarService}. */
final class SoarQueryService {
    SoarQueryService(SoarPlaybookRepository playbooks, PlaybookVersionRepository versions, SoarRunRepository runs,
                SoarDispatchOutboxRepository dispatches, SoarNodeRunRepository nodes, SoarRunEventRepository events,
                SoarApprovalRepository approvals, SoarActionAttemptRepository attempts,
                SoarManualTaskRepository manualTasks, SoarSignalOutboxRepository signals,
                SoarReadModelMapper readModels, SoarRecords records) {
        this.playbooks = playbooks;
        this.versions = versions;
        this.runs = runs;
        this.dispatches = dispatches;
        this.nodes = nodes;
        this.events = events;
        this.approvals = approvals;
        this.attempts = attempts;
        this.manualTasks = manualTasks;
        this.signals = signals;
        this.readModels = readModels;
        this.records = records;
    }

    private final SoarRecords records;
    private final SoarReadModelMapper readModels;
    private final SoarSignalOutboxRepository signals;
    private final SoarManualTaskRepository manualTasks;
    private final SoarActionAttemptRepository attempts;
    private final SoarApprovalRepository approvals;
    private final SoarRunEventRepository events;
    private final SoarNodeRunRepository nodes;
    private final SoarDispatchOutboxRepository dispatches;
    private final SoarRunRepository runs;
    private final PlaybookVersionRepository versions;
    private final SoarPlaybookRepository playbooks;
    private SoarApprovalDecisionRepository approvalDecisions;
    private SoarArtifactRepository artifacts;

    Page<Map<String, Object>> listPlaybooks(Pageable pageable) {
        String tenant = com.socp.platform.tenant.context.TenantContext.require();
        return playbookPage(playbooks.findByTenantId(tenant, pageable));
    }

    Page<Map<String, Object>> listPlaybooks(Pageable pageable, String status,
                                            String ownerName, String tag, String risk) {
        // The repository query applies case functions to the mapped columns.
        // Normalize the bind values in Java so PostgreSQL never has to resolve
        // upper/lower on a nullable, untyped parameter.
        String normalizedStatus = normalizeUpper(status);
        String normalizedOwner = normalizeLower(ownerName);
        String normalizedTag = SoarService.normalizeFilter(tag);
        String normalizedRisk = SoarService.normalizeFilter(risk);
        if (normalizedTag == null && normalizedRisk == null) {
            return playbookPage(playbooks.searchByTenant(com.socp.platform.tenant.context.TenantContext.require(), normalizedStatus, normalizedOwner, null, pageable));
        }
        return playbookPage(playbooks.searchCatalog(com.socp.platform.tenant.context.TenantContext.require(),
                normalizedStatus, normalizedOwner,
                normalizedTag == null ? null : SoarCatalogMetadata.tagToken(normalizedTag),
                normalizedRisk == null ? null : normalizedRisk.toUpperCase(Locale.ROOT), pageable));
    }

    private Page<Map<String, Object>> playbookPage(Page<SoarPlaybookEntity> page) {
        if (page.isEmpty()) return new org.springframework.data.domain.PageImpl<>(List.of(), page.getPageable(), page.getTotalElements());
        List<String> pageIds = page.getContent().stream().map(SoarPlaybookEntity::getId).toList();
        Map<String, Integer> drafts = new LinkedHashMap<>();
        versions.findDraftMetadata(com.socp.platform.tenant.context.TenantContext.require(), pageIds).forEach(
                draft -> drafts.put(draft.getPlaybookId(), draft.getVersionNo()));
        Map<String, Map<String, Object>> latestByPlaybook = new LinkedHashMap<>();
        runs.findLatestMetadataByTenantIdAndPlaybookIds(com.socp.platform.tenant.context.TenantContext.require(),
                page.getContent().stream().map(SoarPlaybookEntity::getId).toList()).forEach(run -> {
                    Map<String, Object> latest = new LinkedHashMap<>();
                    latest.put("runId", run.getId());
                    latest.put("status", run.getStatus());
                    latest.put("createdAt", run.getCreatedAt());
                    latestByPlaybook.put(run.getPlaybookId(), latest);
                });
        return page.map(playbook -> {
            Map<String, Object> view = readModels.playbookView(playbook, drafts.get(playbook.getId()));
            // Null means no retained history, not necessarily never executed.
            view.put("latestRun", latestByPlaybook.get(playbook.getId()));
            return view;
        });
    }

    Map<String, Object> getPlaybook(String id) {
        SoarPlaybookEntity playbook = records.playbook(id);
        List<PlaybookVersionEntity> history = versions
                .findByTenantIdAndPlaybookIdOrderByVersionNoDesc(com.socp.platform.tenant.context.TenantContext.require(), id);
        Integer draft = history.stream().filter(version -> "DRAFT".equals(version.getStatus()))
                .map(PlaybookVersionEntity::getVersionNo).findFirst().orElse(null);
        Map<String, Object> result = readModels.playbookView(playbook, draft);
        result.put("versions", history.stream()
                .map(version -> readModels.versionView(version, playbook.getStatus())).toList());
        return result;
    }

    List<Map<String, Object>> listVersions(String playbookId) {
        SoarPlaybookEntity playbook = records.playbook(playbookId);
        return versions.findByTenantIdAndPlaybookIdOrderByVersionNoDesc(com.socp.platform.tenant.context.TenantContext.require(), playbookId)
                .stream().map(version -> readModels.versionView(version, playbook.getStatus())).toList();
    }

    Map<String, Object> getVersion(String playbookId, int versionNo) {
        return versionView(records.version(playbookId, versionNo));
    }

    Map<String, Object> exportVersion(String playbookId, int versionNo) {
        Map<String, Object> exported = versionView(records.version(playbookId, versionNo));
        exported.put("format", "soar.playbook");
        exported.put("exportedAt", Instant.now());
        return exported;
    }

    Map<String, Object> getVersionById(String versionId) {
        return versions.findByTenantIdAndId(com.socp.platform.tenant.context.TenantContext.require(), versionId)
                .map(this::versionView)
                .orElseThrow(() -> SoarService.error(HttpStatus.NOT_FOUND,
                        "SOAR_VERSION_NOT_FOUND", "version not found"));
    }

    Page<Map<String, Object>> listRuns(Pageable pageable) {
        return runs.findByTenantIdOrderByCreatedAtDesc(com.socp.platform.tenant.context.TenantContext.require(), pageable)
                .map(readModels::runView);
    }

    Page<Map<String, Object>> listRuns(Pageable pageable, String status,
                                       String playbookVersionId, String triggerType,
                                       String requestedBy, Instant createdFrom,
                                       Instant createdTo) {
        return runs.searchByTenant(com.socp.platform.tenant.context.TenantContext.require(), normalizeUpper(status),
                SoarService.normalizeFilter(playbookVersionId), normalizeUpper(triggerType),
                normalizeLower(requestedBy), createdFrom, createdTo, pageable)
                .map(readModels::runView);
    }

    Map<String, Object> getRun(String id) {
        return readModels.runView(records.run(id));
    }

    List<Map<String, Object>> listNodes(String runId) {
        records.run(runId);
        return nodes.findByTenantIdAndRunIdOrderByUpdatedAtAsc(com.socp.platform.tenant.context.TenantContext.require(), runId).stream()
                .map(readModels::nodeView).toList();
    }

    Page<Map<String, Object>> listNodes(String runId, Pageable pageable) {
        records.run(runId);
        return nodes.findByTenantIdAndRunIdOrderByUpdatedAtAsc(com.socp.platform.tenant.context.TenantContext.require(), runId, pageable)
                .map(readModels::nodeView);
    }

    List<Map<String, Object>> listArtifacts(String runId) {
        records.run(runId);
        if (artifacts == null) return List.of();
        return artifacts.findByTenantIdAndRunIdOrderByCreatedAtAsc(com.socp.platform.tenant.context.TenantContext.require(), runId)
                .stream().map(readModels::artifactView).toList();
    }

    Page<Map<String, Object>> listArtifacts(String runId, Pageable pageable) {
        records.run(runId);
        if (artifacts == null) return Page.empty(pageable);
        return artifacts.findByTenantIdAndRunIdOrderByCreatedAtAsc(com.socp.platform.tenant.context.TenantContext.require(), runId, pageable)
                .map(readModels::artifactView);
    }

    Map<String, Object> getArtifact(String id) {
        return readModels.artifactView(records.artifact(id));
    }

    Page<Map<String, Object>> listNodeAttempts(String nodeRunId, Pageable pageable) {
        String tenant = com.socp.platform.tenant.context.TenantContext.require();
        Optional<SoarNodeRunEntity> lockedNode = nodes.findByTenantIdAndIdForUpdate(tenant, nodeRunId);
        if (lockedNode == null) lockedNode = nodes.findByTenantIdAndId(tenant, nodeRunId);
        SoarNodeRunEntity node = (lockedNode == null ? Optional.<SoarNodeRunEntity>empty() : lockedNode)
                .orElseThrow(() -> SoarService.error(HttpStatus.NOT_FOUND,
                        "SOAR_NODE_RUN_NOT_FOUND", "node run not found"));
        return attempts.findByTenantIdAndNodeRunIdOrderByAttemptNoAsc(tenant, node.getId(), pageable)
                .map(readModels::attemptView);
    }

    List<Map<String, Object>> listEvents(String runId) {
        records.run(runId);
        return events.findByTenantIdAndRunIdOrderBySequenceNoAsc(com.socp.platform.tenant.context.TenantContext.require(), runId).stream()
                .map(readModels::eventView).toList();
    }

    Page<Map<String, Object>> listEvents(String runId, long afterSequence, Pageable pageable) {
        records.run(runId);
        return events.findByTenantIdAndRunIdAndSequenceNoGreaterThanOrderBySequenceNoAsc(
                        com.socp.platform.tenant.context.TenantContext.require(), runId, Math.max(0, afterSequence), pageable)
                .map(readModels::eventView);
    }

    List<Map<String, Object>> listManualTasks(boolean pendingOnly) {
        List<SoarManualTaskEntity> rows = pendingOnly
                ? manualTasks.findByTenantIdAndStatusOrderByDueAtAsc(com.socp.platform.tenant.context.TenantContext.require(), "PENDING")
                : manualTasks.findByTenantIdOrderByCreatedAtDesc(com.socp.platform.tenant.context.TenantContext.require());
        return rows.stream().map(readModels::manualTaskView).toList();
    }

    Page<Map<String, Object>> listManualTasks(boolean pendingOnly, Pageable pageable) {
        if (pendingOnly) {
            return manualTasks.findByTenantIdAndStatusOrderByDueAtAsc(com.socp.platform.tenant.context.TenantContext.require(), "PENDING", pageable)
                    .map(readModels::manualTaskView);
        }
        return manualTasks.findByTenantIdOrderByCreatedAtDesc(com.socp.platform.tenant.context.TenantContext.require(), pageable)
                .map(readModels::manualTaskView);
    }

    Map<String, Object> stats() {
        String tenant = com.socp.platform.tenant.context.TenantContext.require();
        Map<String, Long> byStatus = new LinkedHashMap<>();
        for (SoarRunStatus status : SoarRunStatus.values()) {
            long count = runs.countByTenantIdAndStatus(tenant, status.name());
            if (count > 0) byStatus.put(status.name(), count);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("runsByStatus", byStatus);
        out.put("dispatchBacklog", dispatches.countByTenantIdAndStatusAndNextAttemptAtLessThanEqual(
                tenant, "PENDING", Instant.now()));
        out.put("signalBacklog", signals == null ? 0
                : signals.countByTenantIdAndStatus(tenant, "PENDING"));
        out.put("generatedAt", Instant.now());
        return out;
    }

    Map<String, Object> healthBacklog() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("dispatchBacklog", dispatches.countByStatus("PENDING"));
        out.put("dispatchDead", dispatches.countByStatus("DEAD"));
        if (signals != null) {
            out.put("signalBacklog", signals.countByStatus("PENDING"));
            out.put("signalDead", signals.countByStatus("DEAD"));
        }
        return out;
    }

    List<Map<String, Object>> deadDispatches() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (SoarDispatchOutboxEntity row : dispatches
                .findByTenantIdAndStatusOrderByUpdatedAtAsc(com.socp.platform.tenant.context.TenantContext.require(), "DEAD")) {
            result.add(Map.of("id", row.getId(), "runId", row.getRunId(), "status", row.getStatus(),
                    "attempts", row.getAttempts(), "lastError", SoarService.redactFreeText(row.getLastError(), 2048),
                    "updatedAt", row.getUpdatedAt()));
        }
        if (signals != null) {
            for (SoarSignalOutboxEntity row : signals
                    .findByTenantIdAndStatusOrderByUpdatedAtAsc(com.socp.platform.tenant.context.TenantContext.require(), "DEAD")) {
                result.add(Map.of("id", row.getId(), "runId", row.getRunId(), "kind", "SIGNAL",
                        "status", row.getStatus(), "signalType", nullSafe(row.getSignalType()),
                        "signalKey", nullSafe(row.getSignalKey()), "attempts", row.getAttempts(),
                        "lastError", SoarService.redactFreeText(row.getLastError(), 2048),
                        "updatedAt", row.getUpdatedAt()));
            }
        }
        return result;
    }

    List<Map<String, Object>> listApprovals() {
        return listApprovals(org.springframework.data.domain.PageRequest.of(0, 200)).getContent();
    }

    Page<Map<String, Object>> listApprovals(Pageable pageable) {
        return listApprovals(pageable, null);
    }

    Map<String, Object> getApproval(String id) {
        String tenant = com.socp.platform.tenant.context.TenantContext.require();
        var approval = approvals.findByTenantIdAndId(tenant, id).orElseThrow(() ->
                new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND, "approval not found"));
        return readModels.approvalView(approval, approvalDecisions == null ? List.of()
                : approvalDecisions.findByTenantIdAndApprovalIdOrderByCreatedAtAsc(tenant, id));
    }

    Page<Map<String, Object>> listApprovals(Pageable pageable, String status) {
        String tenant = com.socp.platform.tenant.context.TenantContext.require();
        String filter = status == null || status.isBlank() ? null : status.trim().toUpperCase(java.util.Locale.ROOT);
        if (filter != null && !java.util.Set.of("PENDING", "APPROVED", "REJECTED", "EXPIRED", "CANCELLED").contains(filter)) {
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST, "invalid approval status");
        }
        var page = filter == null ? approvals.findByTenantIdOrderByCreatedAtDesc(tenant, pageable)
                : approvals.findByTenantIdAndStatusOrderByCreatedAtDesc(tenant, filter, pageable);
        if (page.isEmpty()) return new org.springframework.data.domain.PageImpl<>(List.of(), page.getPageable(), page.getTotalElements());
        Map<String, List<com.socp.soar.web.persistence.entity.SoarApprovalDecisionEntity>> votes = new LinkedHashMap<>();
        if (approvalDecisions != null) {
            approvalDecisions.findByTenantIdAndApprovalIdInOrderByCreatedAtAsc(tenant,
                    page.getContent().stream().map(com.socp.soar.web.persistence.entity.SoarApprovalEntity::getId).toList())
                    .forEach(vote -> votes.computeIfAbsent(vote.getApprovalId(), ignored -> new ArrayList<>()).add(vote));
        }
        return page.map(approval -> readModels.approvalView(approval, votes.getOrDefault(approval.getId(), List.of())));
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }

    private static String normalizeUpper(String value) {
        String normalized = SoarService.normalizeFilter(value);
        return normalized == null ? null : normalized.toUpperCase(Locale.ROOT);
    }

    private static String normalizeLower(String value) {
        String normalized = SoarService.normalizeFilter(value);
        return normalized == null ? null : normalized.toLowerCase(Locale.ROOT);
    }
    private Map<String, Object> versionView(PlaybookVersionEntity value) {
        return readModels.versionView(value, records.playbook(value.getPlaybookId()).getStatus());
    }
    private Map<String, Object> approvalView(com.socp.soar.web.persistence.entity.SoarApprovalEntity approval) {
        return readModels.approvalView(approval, approvalDecisions == null ? List.of()
                : approvalDecisions.findByTenantIdAndApprovalIdOrderByCreatedAtAsc(
                    com.socp.platform.tenant.context.TenantContext.require(), approval.getId()));
    }
    void setArtifacts(SoarArtifactRepository value) { this.artifacts = value; }
    void setApprovalDecisions(SoarApprovalDecisionRepository value) { this.approvalDecisions = value; }
}
