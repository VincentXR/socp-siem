package com.socp.soar.web.service;

import com.socp.soar.web.domain.SoarRunStatus;
import com.socp.soar.web.persistence.entity.SoarDispatchOutboxEntity;
import com.socp.soar.web.persistence.entity.SoarManualTaskEntity;
import com.socp.soar.web.persistence.entity.SoarNodeRunEntity;
import com.socp.soar.web.persistence.entity.SoarPlaybookEntity;
import com.socp.soar.web.persistence.entity.PlaybookVersionEntity;
import com.socp.soar.web.persistence.entity.SoarSignalOutboxEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** Tenant-scoped read queries and projection selection for {@link SoarService}. */
final class SoarQueryService {

    private final SoarService owner;

    SoarQueryService(SoarService owner) {
        this.owner = owner;
    }

    Page<Map<String, Object>> listPlaybooks(Pageable pageable) {
        String tenant = owner.tenant();
        return owner.playbooks.findByTenantId(tenant, pageable).map(owner::playbookView);
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
            return owner.playbooks.searchByTenant(owner.tenant(), normalizedStatus, normalizedOwner, null, pageable)
                    .map(owner::playbookView);
        }
        List<SoarPlaybookEntity> candidates = owner.playbooks.findByTenantId(owner.tenant()).stream()
                .filter(row -> normalizedStatus == null || normalizedStatus.equalsIgnoreCase(row.getStatus()))
                .filter(row -> normalizedOwner == null || normalizedOwner.equalsIgnoreCase(row.getOwner()))
                .filter(row -> normalizedTag == null || owner.hasTag(row, normalizedTag))
                .filter(row -> normalizedRisk == null || owner.riskMatches(row, normalizedRisk))
                .sorted(Comparator.comparing(SoarPlaybookEntity::getUpdatedAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
        int pageNumber = Math.max(0, pageable.getPageNumber());
        int from = Math.min(candidates.size(), pageNumber * pageable.getPageSize());
        int to = Math.min(candidates.size(), from + pageable.getPageSize());
        List<Map<String, Object>> content = candidates.subList(from, to).stream()
                .map(owner::playbookView).toList();
        return new PageImpl<>(content, pageable, candidates.size());
    }

    Map<String, Object> getPlaybook(String id) {
        SoarPlaybookEntity playbook = owner.playbook(id);
        List<PlaybookVersionEntity> history = owner.versions
                .findByTenantIdAndPlaybookIdOrderByVersionNoDesc(owner.tenant(), id);
        Map<String, Object> result = owner.playbookView(playbook);
        result.put("versions", history.stream().map(owner::versionView).toList());
        return result;
    }

    List<Map<String, Object>> listVersions(String playbookId) {
        owner.playbook(playbookId);
        return owner.versions.findByTenantIdAndPlaybookIdOrderByVersionNoDesc(owner.tenant(), playbookId)
                .stream().map(owner::versionView).toList();
    }

    Map<String, Object> getVersion(String playbookId, int versionNo) {
        return owner.versionView(owner.version(playbookId, versionNo));
    }

    Map<String, Object> exportVersion(String playbookId, int versionNo) {
        Map<String, Object> exported = owner.versionView(owner.version(playbookId, versionNo));
        exported.put("format", "soar.playbook");
        exported.put("exportedAt", Instant.now());
        return exported;
    }

    Map<String, Object> getVersionById(String versionId) {
        return owner.versions.findByTenantIdAndId(owner.tenant(), versionId)
                .map(owner::versionView)
                .orElseThrow(() -> SoarService.error(HttpStatus.NOT_FOUND,
                        "SOAR_VERSION_NOT_FOUND", "version not found"));
    }

    Page<Map<String, Object>> listRuns(Pageable pageable) {
        return owner.runs.findByTenantIdOrderByCreatedAtDesc(owner.tenant(), pageable)
                .map(owner::runView);
    }

    Page<Map<String, Object>> listRuns(Pageable pageable, String status,
                                       String playbookVersionId, String triggerType,
                                       String requestedBy, Instant createdFrom,
                                       Instant createdTo) {
        return owner.runs.searchByTenant(owner.tenant(), normalizeUpper(status),
                SoarService.normalizeFilter(playbookVersionId), normalizeUpper(triggerType),
                normalizeLower(requestedBy), createdFrom, createdTo, pageable)
                .map(owner::runView);
    }

    Map<String, Object> getRun(String id) {
        return owner.runView(owner.run(id));
    }

    List<Map<String, Object>> listNodes(String runId) {
        owner.run(runId);
        return owner.nodes.findByTenantIdAndRunIdOrderByUpdatedAtAsc(owner.tenant(), runId).stream()
                .map(owner::nodeView).toList();
    }

    Page<Map<String, Object>> listNodes(String runId, Pageable pageable) {
        owner.run(runId);
        return owner.nodes.findByTenantIdAndRunIdOrderByUpdatedAtAsc(owner.tenant(), runId, pageable)
                .map(owner::nodeView);
    }

    List<Map<String, Object>> listArtifacts(String runId) {
        owner.run(runId);
        if (owner.artifacts == null) return List.of();
        return owner.artifacts.findByTenantIdAndRunIdOrderByCreatedAtAsc(owner.tenant(), runId)
                .stream().map(owner::artifactView).toList();
    }

    Page<Map<String, Object>> listArtifacts(String runId, Pageable pageable) {
        owner.run(runId);
        if (owner.artifacts == null) return Page.empty(pageable);
        return owner.artifacts.findByTenantIdAndRunIdOrderByCreatedAtAsc(owner.tenant(), runId, pageable)
                .map(owner::artifactView);
    }

    Map<String, Object> getArtifact(String id) {
        return owner.artifactView(owner.artifact(id));
    }

    Page<Map<String, Object>> listNodeAttempts(String nodeRunId, Pageable pageable) {
        String tenant = owner.tenant();
        Optional<SoarNodeRunEntity> lockedNode = owner.nodes.findByTenantIdAndIdForUpdate(tenant, nodeRunId);
        if (lockedNode == null) lockedNode = owner.nodes.findByTenantIdAndId(tenant, nodeRunId);
        SoarNodeRunEntity node = (lockedNode == null ? Optional.<SoarNodeRunEntity>empty() : lockedNode)
                .orElseThrow(() -> SoarService.error(HttpStatus.NOT_FOUND,
                        "SOAR_NODE_RUN_NOT_FOUND", "node run not found"));
        return owner.attempts.findByTenantIdAndNodeRunIdOrderByAttemptNoAsc(tenant, node.getId(), pageable)
                .map(owner::attemptView);
    }

    List<Map<String, Object>> listEvents(String runId) {
        owner.run(runId);
        return owner.events.findByTenantIdAndRunIdOrderBySequenceNoAsc(owner.tenant(), runId).stream()
                .map(owner::eventView).toList();
    }

    Page<Map<String, Object>> listEvents(String runId, long afterSequence, Pageable pageable) {
        owner.run(runId);
        return owner.events.findByTenantIdAndRunIdAndSequenceNoGreaterThanOrderBySequenceNoAsc(
                        owner.tenant(), runId, Math.max(0, afterSequence), pageable)
                .map(owner::eventView);
    }

    List<Map<String, Object>> listManualTasks(boolean pendingOnly) {
        List<SoarManualTaskEntity> rows = pendingOnly
                ? owner.manualTasks.findByTenantIdAndStatusOrderByDueAtAsc(owner.tenant(), "PENDING")
                : owner.manualTasks.findByTenantIdOrderByCreatedAtDesc(owner.tenant());
        return rows.stream().map(owner::manualTaskView).toList();
    }

    Page<Map<String, Object>> listManualTasks(boolean pendingOnly, Pageable pageable) {
        if (pendingOnly) {
            Page<SoarManualTaskEntity> pending = owner.manualTasks
                    .findByTenantIdAndStatusOrderByDueAtAsc(owner.tenant(), "PENDING", pageable);
            if (pending != null) {
                return pending.map(owner::manualTaskView);
            }
            // Keep isolated compatibility tests and custom repository adapters
            // working while the Spring Data implementation is database-paged.
            // Production repositories always take the branch above.
            List<SoarManualTaskEntity> all = owner.manualTasks.findByTenantIdAndStatusOrderByDueAtAsc(
                    owner.tenant(), "PENDING");
            int from = Math.min(all.size(), Math.max(0, pageable.getPageNumber()) * pageable.getPageSize());
            int to = Math.min(all.size(), from + pageable.getPageSize());
            return new PageImpl<>(all.subList(from, to).stream().map(owner::manualTaskView).toList(),
                    pageable, all.size());
        }
        return owner.manualTasks.findByTenantIdOrderByCreatedAtDesc(owner.tenant(), pageable)
                .map(owner::manualTaskView);
    }

    Map<String, Object> stats() {
        String tenant = owner.tenant();
        Map<String, Long> byStatus = new LinkedHashMap<>();
        for (SoarRunStatus status : SoarRunStatus.values()) {
            long count = owner.runs.countByTenantIdAndStatus(tenant, status.name());
            if (count > 0) byStatus.put(status.name(), count);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("runsByStatus", byStatus);
        out.put("dispatchBacklog", owner.dispatches.countByTenantIdAndStatusAndNextAttemptAtLessThanEqual(
                tenant, "PENDING", Instant.now()));
        out.put("signalBacklog", owner.signals == null ? 0
                : owner.signals.countByTenantIdAndStatus(tenant, "PENDING"));
        out.put("generatedAt", Instant.now());
        return out;
    }

    Map<String, Object> healthBacklog() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("dispatchBacklog", owner.dispatches.countByStatus("PENDING"));
        out.put("dispatchDead", owner.dispatches.countByStatus("DEAD"));
        if (owner.signals != null) {
            out.put("signalBacklog", owner.signals.countByStatus("PENDING"));
            out.put("signalDead", owner.signals.countByStatus("DEAD"));
        }
        return out;
    }

    List<Map<String, Object>> deadDispatches() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (SoarDispatchOutboxEntity row : owner.dispatches
                .findByTenantIdAndStatusOrderByUpdatedAtAsc(owner.tenant(), "DEAD")) {
            result.add(Map.of("id", row.getId(), "runId", row.getRunId(), "status", row.getStatus(),
                    "attempts", row.getAttempts(), "lastError", SoarService.redactFreeText(row.getLastError(), 2048),
                    "updatedAt", row.getUpdatedAt()));
        }
        if (owner.signals != null) {
            for (SoarSignalOutboxEntity row : owner.signals
                    .findByTenantIdAndStatusOrderByUpdatedAtAsc(owner.tenant(), "DEAD")) {
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
        return owner.approvals.findByTenantIdOrderByCreatedAtDesc(owner.tenant()).stream()
                .map(owner::approvalView).toList();
    }

    Page<Map<String, Object>> listApprovals(Pageable pageable) {
        return owner.approvals.findByTenantIdOrderByCreatedAtDesc(owner.tenant(), pageable)
                .map(owner::approvalView);
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
}
