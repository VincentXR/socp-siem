package com.socp.soar.web.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.socp.platform.tenant.context.TenantContext;
import com.socp.soar.web.artifact.SoarArtifactStore;
import com.socp.soar.web.domain.SoarRunStatus;
import com.socp.soar.web.persistence.entity.SoarActionAttemptEntity;
import com.socp.soar.web.persistence.entity.SoarApprovalDecisionEntity;
import com.socp.soar.web.persistence.entity.SoarApprovalEntity;
import com.socp.soar.web.persistence.entity.SoarArtifactEntity;
import com.socp.soar.web.persistence.entity.SoarDispatchOutboxEntity;
import com.socp.soar.web.persistence.entity.SoarManualTaskEntity;
import com.socp.soar.web.persistence.entity.SoarNodeRunEntity;
import com.socp.soar.web.persistence.entity.SoarRunEntity;
import com.socp.soar.web.persistence.entity.SoarRunEventEntity;
import com.socp.soar.web.persistence.entity.SoarSignalOutboxEntity;
import com.socp.soar.web.persistence.repository.SoarActionAttemptRepository;
import com.socp.soar.web.persistence.repository.SoarApprovalDecisionRepository;
import com.socp.soar.web.persistence.repository.SoarApprovalRepository;
import com.socp.soar.web.persistence.repository.SoarArtifactRepository;
import com.socp.soar.web.persistence.repository.SoarDispatchOutboxRepository;
import com.socp.soar.web.persistence.repository.SoarManualTaskRepository;
import com.socp.soar.web.persistence.repository.SoarNodeRunRepository;
import com.socp.soar.web.persistence.repository.SoarRunEventRepository;
import com.socp.soar.web.persistence.repository.SoarRunRepository;
import com.socp.soar.web.persistence.repository.SoarSignalOutboxRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;

/**
 * Tenant-scoped read model for SOAR executions.
 *
 * <p>Run, evidence, approval and backlog reads are intentionally isolated
 * from the command service.  This keeps query pagination and redaction rules
 * in one application boundary while command transactions remain in
 * {@link SoarService}.</p>
 */
@Service
public class SoarRunQueryService {

    private static final long MAX_ARTIFACT_BYTES = 10L * 1024 * 1024;

    private final SoarRunRepository runs;
    private final SoarNodeRunRepository nodes;
    private final SoarRunEventRepository events;
    private final SoarActionAttemptRepository attempts;
    private final SoarManualTaskRepository manualTasks;
    private final SoarApprovalRepository approvals;
    private final SoarDispatchOutboxRepository dispatchOutbox;
    private final SoarSignalOutboxRepository signals;
    private final ObjectMapper mapper;
    private SoarArtifactRepository artifacts;
    private SoarApprovalDecisionRepository approvalDecisions;
    private SoarArtifactStore artifactStore;

    @Autowired
    public SoarRunQueryService(SoarRunRepository runs,
                               SoarDispatchOutboxRepository dispatches,
                               SoarNodeRunRepository nodes,
                               SoarRunEventRepository events,
                               SoarActionAttemptRepository attempts,
                               SoarManualTaskRepository manualTasks,
                               SoarApprovalRepository approvals,
                               SoarSignalOutboxRepository signals,
                               ObjectMapper mapper) {
        this.runs = runs;
        this.dispatchOutbox = dispatches;
        this.nodes = nodes;
        this.events = events;
        this.attempts = attempts;
        this.manualTasks = manualTasks;
        this.approvals = approvals;
        this.signals = signals;
        this.mapper = mapper;
    }

    @Autowired(required = false)
    public void setArtifacts(SoarArtifactRepository artifacts) {
        this.artifacts = artifacts;
    }

    @Autowired(required = false)
    public void setApprovalDecisions(SoarApprovalDecisionRepository approvalDecisions) {
        this.approvalDecisions = approvalDecisions;
    }

    @Autowired(required = false)
    public void setArtifactStore(SoarArtifactStore artifactStore) {
        this.artifactStore = artifactStore;
    }

    @Transactional(readOnly = true)
    public Page<Map<String, Object>> listRuns(Pageable pageable, String status,
                                              String playbookVersionId, String triggerType,
                                              String requestedBy, Instant createdFrom,
                                              Instant createdTo) {
        return runs.searchByTenant(tenant(), normalize(status), normalize(playbookVersionId),
                normalize(triggerType), normalize(requestedBy), createdFrom, createdTo, pageable)
                .map(this::runView);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getRun(String id) {
        return runView(run(id));
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listNodes(String runId) {
        run(runId);
        return nodes.findByTenantIdAndRunIdOrderByUpdatedAtAsc(tenant(), runId)
                .stream().map(this::nodeView).toList();
    }

    @Transactional(readOnly = true)
    public Page<Map<String, Object>> listNodes(String runId, Pageable pageable) {
        run(runId);
        return nodes.findByTenantIdAndRunIdOrderByUpdatedAtAsc(tenant(), runId, pageable)
                .map(this::nodeView);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listArtifacts(String runId) {
        run(runId);
        if (artifacts == null) return List.of();
        return artifacts.findByTenantIdAndRunIdOrderByCreatedAtAsc(tenant(), runId)
                .stream().map(this::artifactView).toList();
    }

    @Transactional(readOnly = true)
    public Page<Map<String, Object>> listArtifacts(String runId, Pageable pageable) {
        run(runId);
        if (artifacts == null) return Page.empty(pageable);
        return artifacts.findByTenantIdAndRunIdOrderByCreatedAtAsc(tenant(), runId, pageable)
                .map(this::artifactView);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getArtifact(String id) {
        return artifactView(artifact(id));
    }

    @Transactional(readOnly = true)
    public String getArtifactContent(String id) {
        SoarArtifactEntity artifact = artifact(id);
        if (artifact.getInlineJson() != null) return artifact.getInlineJson();
        if (artifactStore == null) {
            throw error(HttpStatus.SERVICE_UNAVAILABLE, "SOAR_ARTIFACT_CONTENT_UNAVAILABLE",
                    "artifact storage adapter cannot serve this artifact");
        }
        try {
            return artifactStore.read(artifact.getStorageRef())
                    .map(bytes -> decodeExternalArtifact(artifact, bytes))
                    .orElseThrow(() -> error(HttpStatus.GONE, "SOAR_ARTIFACT_CONTENT_GONE",
                            "artifact content is no longer available"));
        } catch (ResponseStatusException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw error(HttpStatus.SERVICE_UNAVAILABLE, "SOAR_ARTIFACT_CONTENT_UNAVAILABLE",
                    "artifact storage could not serve the content");
        }
    }

    @Transactional(readOnly = true)
    public Page<Map<String, Object>> listNodeAttempts(String nodeRunId, Pageable pageable) {
        SoarNodeRunEntity node = nodes.findByTenantIdAndId(tenant(), nodeRunId)
                .orElseThrow(() -> error(HttpStatus.NOT_FOUND, "SOAR_NODE_RUN_NOT_FOUND", "node run not found"));
        return attempts.findByTenantIdAndNodeRunIdOrderByAttemptNoAsc(tenant(), node.getId(), pageable)
                .map(this::attemptView);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listEvents(String runId) {
        run(runId);
        return events.findByTenantIdAndRunIdOrderBySequenceNoAsc(tenant(), runId)
                .stream().map(this::eventView).toList();
    }

    @Transactional(readOnly = true)
    public Page<Map<String, Object>> listEvents(String runId, long afterSequence, Pageable pageable) {
        run(runId);
        return events.findByTenantIdAndRunIdAndSequenceNoGreaterThanOrderBySequenceNoAsc(
                        tenant(), runId, Math.max(0, afterSequence), pageable)
                .map(this::eventView);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listManualTasks(boolean pendingOnly) {
        List<SoarManualTaskEntity> rows = pendingOnly
                ? manualTasks.findByTenantIdAndStatusOrderByDueAtAsc(tenant(), "PENDING")
                : manualTasks.findByTenantIdOrderByCreatedAtDesc(tenant());
        return rows.stream().map(this::manualTaskView).toList();
    }

    @Transactional(readOnly = true)
    public Page<Map<String, Object>> listManualTasks(boolean pendingOnly, Pageable pageable) {
        if (!pendingOnly) {
            return manualTasks.findByTenantIdOrderByCreatedAtDesc(tenant(), pageable).map(this::manualTaskView);
        }
        List<SoarManualTaskEntity> all = manualTasks.findByTenantIdAndStatusOrderByDueAtAsc(tenant(), "PENDING");
        int from = Math.min(all.size(), Math.max(0, pageable.getPageNumber()) * pageable.getPageSize());
        int to = Math.min(all.size(), from + pageable.getPageSize());
        return new PageImpl<>(all.subList(from, to).stream().map(this::manualTaskView).toList(),
                pageable, all.size());
    }

    @Transactional(readOnly = true)
    public Map<String, Object> stats() {
        String tenant = tenant();
        Map<String, Long> byStatus = new LinkedHashMap<>();
        for (SoarRunStatus status : SoarRunStatus.values()) {
            long count = runs.countByTenantIdAndStatus(tenant, status.name());
            if (count > 0) byStatus.put(status.name(), count);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("runsByStatus", byStatus);
        out.put("dispatchBacklog", dispatchOutbox.countByTenantIdAndStatusAndNextAttemptAtLessThanEqual(
                tenant, "PENDING", Instant.now()));
        out.put("signalBacklog", signals == null ? 0 : signals.countByTenantIdAndStatus(tenant, "PENDING"));
        out.put("generatedAt", Instant.now());
        return out;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> healthBacklog() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("dispatchBacklog", dispatchOutbox.countByStatus("PENDING"));
        out.put("dispatchDead", dispatchOutbox.countByStatus("DEAD"));
        if (signals != null) {
            out.put("signalBacklog", signals.countByStatus("PENDING"));
            out.put("signalDead", signals.countByStatus("DEAD"));
        }
        return out;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> deadDispatches() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (SoarDispatchOutboxEntity row : dispatchOutbox.findByTenantIdAndStatusOrderByUpdatedAtAsc(
                tenant(), "DEAD")) {
            result.add(Map.of("id", row.getId(), "runId", row.getRunId(), "status", row.getStatus(),
                    "attempts", row.getAttempts(), "lastError", redactFreeText(row.getLastError(), 2048),
                    "updatedAt", row.getUpdatedAt()));
        }
        if (signals != null) for (SoarSignalOutboxEntity row : signals.findByTenantIdAndStatusOrderByUpdatedAtAsc(
                tenant(), "DEAD")) {
            result.add(Map.of("id", row.getId(), "runId", row.getRunId(), "kind", "SIGNAL",
                    "status", row.getStatus(), "signalType", nullSafe(row.getSignalType()),
                    "signalKey", nullSafe(row.getSignalKey()), "attempts", row.getAttempts(),
                    "lastError", redactFreeText(row.getLastError(), 2048), "updatedAt", row.getUpdatedAt()));
        }
        return result;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listApprovals() {
        return approvals.findByTenantIdOrderByCreatedAtDesc(tenant()).stream()
                .map(this::approvalView).toList();
    }

    @Transactional(readOnly = true)
    public Page<Map<String, Object>> listApprovals(Pageable pageable) {
        return approvals.findByTenantIdOrderByCreatedAtDesc(tenant(), pageable).map(this::approvalView);
    }

    private SoarRunEntity run(String id) {
        return runs.findByTenantIdAndId(tenant(), id)
                .orElseThrow(() -> error(HttpStatus.NOT_FOUND, "SOAR_RUN_NOT_FOUND", "run not found"));
    }

    private SoarArtifactEntity artifact(String id) {
        if (artifacts == null) {
            throw error(HttpStatus.SERVICE_UNAVAILABLE, "SOAR_ARTIFACT_STORAGE_UNAVAILABLE",
                    "artifact storage adapter is not configured");
        }
        SoarArtifactEntity value = artifacts.findByTenantIdAndId(tenant(), id)
                .orElseThrow(() -> error(HttpStatus.NOT_FOUND, "SOAR_ARTIFACT_NOT_FOUND", "artifact not found"));
        if (value.getExpiresAt() != null && value.getExpiresAt().isBefore(Instant.now())) {
            throw error(HttpStatus.GONE, "SOAR_ARTIFACT_EXPIRED", "artifact has expired");
        }
        return value;
    }

    private Map<String, Object> runView(SoarRunEntity run) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("runId", run.getId());
        result.put("requestId", run.getRequestId());
        result.put("executionSeriesId", run.getExecutionSeriesId());
        result.put("playbookId", run.getPlaybookId());
        result.put("playbookVersionId", run.getPlaybookVersionId());
        result.put("playbookVersion", run.getPlaybookVersionNo());
        result.put("definitionHash", run.getDefinitionHash());
        result.put("triggerType", run.getTriggerType());
        result.put("subject", Map.of("type", nullSafe(run.getSubjectType()), "id", nullSafe(run.getSubjectId())));
        result.put("status", run.getStatus());
        result.put("executionNodeCount", run.getExecutionNodeCount() == null ? 0 : run.getExecutionNodeCount());
        result.put("temporalWorkflowId", run.getTemporalWorkflowId());
        result.put("temporalRunId", run.getTemporalRunId());
        if (run.getErrorCode() != null) result.put("errorCode", run.getErrorCode());
        if (run.getErrorMessage() != null) result.put("errorMessage", redactFreeText(run.getErrorMessage(), 2048));
        result.put("requestedBy", run.getRequestedBy());
        result.put("createdAt", run.getCreatedAt());
        result.put("startedAt", run.getStartedAt());
        result.put("completedAt", run.getCompletedAt());
        result.put("updatedAt", run.getUpdatedAt());
        return result;
    }

    private Map<String, Object> nodeView(SoarNodeRunEntity node) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", node.getId()); result.put("runId", node.getRunId()); result.put("nodeId", node.getNodeId());
        result.put("iterationPath", node.getIterationPath()); result.put("nodeType", node.getNodeType());
        result.put("status", node.getStatus()); result.put("input", redactedTree(node.getInputJson()));
        result.put("output", redactedTree(node.getOutputJson()));
        result.put("idempotencyKey", nullSafe(node.getIdempotencyKey()));
        result.put("connectionId", nullSafe(node.getConnectionId()));
        result.put("connectionRevision", node.getConnectionRevision());
        result.put("errorCode", nullSafe(node.getErrorCode()));
        result.put("errorMessage", redactFreeText(node.getErrorMessage(), 2048));
        result.put("startedAt", node.getStartedAt()); result.put("completedAt", node.getCompletedAt());
        result.put("updatedAt", node.getUpdatedAt());
        return result;
    }

    private Map<String, Object> artifactView(SoarArtifactEntity artifact) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", artifact.getId()); result.put("runId", artifact.getRunId());
        result.put("nodeRunId", nullSafe(artifact.getNodeRunId())); result.put("mediaType", artifact.getMediaType());
        result.put("sizeBytes", artifact.getSizeBytes()); result.put("sha256", artifact.getSha256());
        result.put("storageRef", artifact.getStorageRef()); result.put("classification", artifact.getClassification());
        result.put("expiresAt", artifact.getExpiresAt()); result.put("createdAt", artifact.getCreatedAt());
        return result;
    }

    private Map<String, Object> eventView(SoarRunEventEntity event) {
        return Map.of("id", event.getId(), "runId", event.getRunId(), "nodeRunId", nullSafe(event.getNodeRunId()),
                "sequence", event.getSequenceNo(), "eventType", event.getEventType(), "actor", nullSafe(event.getActor()),
                "summary", event.getSummary(), "detail", redactedTree(event.getDetailJson()),
                "traceId", nullSafe(event.getTraceId()), "createdAt", event.getCreatedAt());
    }

    private Map<String, Object> attemptView(SoarActionAttemptEntity attempt) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", attempt.getId()); result.put("nodeRunId", attempt.getNodeRunId());
        result.put("attemptNo", attempt.getAttemptNo()); result.put("status", attempt.getStatus());
        result.put("requestHash", attempt.getRequestHash()); result.put("remoteOperationId", attempt.getRemoteOperationId());
        result.put("connectionId", nullSafe(attempt.getConnectionId()));
        result.put("connectionRevision", attempt.getConnectionRevision()); result.put("remoteTime", attempt.getRemoteTime());
        result.put("receipt", redactedTree(attempt.getReceiptJson())); result.put("errorCode", attempt.getErrorCode());
        result.put("errorMessage", redactFreeText(attempt.getErrorMessage(), 2048));
        result.put("retryable", attempt.isRetryable()); result.put("startedAt", attempt.getStartedAt());
        result.put("completedAt", attempt.getCompletedAt());
        return result;
    }

    private Map<String, Object> manualTaskView(SoarManualTaskEntity task) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", task.getId()); result.put("runId", task.getRunId()); result.put("nodeId", task.getNodeId());
        result.put("formSchema", readTree(task.getFormSchemaJson())); result.put("input", redactedTree(task.getInputJson()));
        result.put("assignee", nullSafe(task.getAssignee())); result.put("status", task.getStatus());
        result.put("dueAt", task.getDueAt()); result.put("completedBy", nullSafe(task.getCompletedBy()));
        result.put("completedAt", task.getCompletedAt()); result.put("createdAt", task.getCreatedAt());
        return result;
    }

    private Map<String, Object> approvalView(SoarApprovalEntity approval) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", approval.getId()); result.put("runId", approval.getRunId());
        result.put("approvalKey", nullSafe(approval.getApprovalKey())); result.put("nodeRunId", nullSafe(approval.getNodeRunId()));
        result.put("actionRef", nullSafe(approval.getActionRef())); result.put("inputHash", nullSafe(approval.getInputHash()));
        result.put("targetSnapshot", redactedTree(approval.getTargetSnapshotJson()));
        result.put("approvalPolicy", redactedTree(approval.getPolicyJson()));
        result.put("requiredApprovals", approval.getRequiredApprovals());
        List<Map<String, Object>> votes = approvalVotes(approval);
        long approved = votes.stream().filter(vote -> "APPROVE".equals(vote.get("decision"))).count();
        if (approved == 0 && "APPROVED".equalsIgnoreCase(approval.getStatus())) {
            approved = Math.min(1, Math.max(1, approval.getRequiredApprovals()));
        }
        result.put("approvedVotes", approved); result.put("decisions", votes); result.put("status", approval.getStatus());
        result.put("requestedBy", approval.getRequestedBy()); result.put("approver", nullSafe(approval.getApprover()));
        result.put("reason", redactFreeText(approval.getReason(), 2048));
        result.put("decisionReason", redactFreeText(approval.getDecisionReason(), 2048));
        result.put("createdAt", approval.getCreatedAt()); result.put("expiresAt", approval.getExpiresAt());
        result.put("decidedAt", approval.getDecidedAt());
        return result;
    }

    private List<Map<String, Object>> approvalVotes(SoarApprovalEntity approval) {
        if (approvalDecisions == null || approval == null || approval.getId() == null) return List.of();
        List<SoarApprovalDecisionEntity> rows = approvalDecisions
                .findByTenantIdAndApprovalIdOrderByCreatedAtAsc(tenant(), approval.getId());
        if (rows == null) return List.of();
        return rows.stream().map(vote -> Map.<String, Object>of(
                "id", vote.getId(), "actor", vote.getActorId(), "decision", vote.getDecision(),
                "reason", redactFreeText(vote.getReason(), 2048), "createdAt", vote.getCreatedAt())).toList();
    }

    private String decodeExternalArtifact(SoarArtifactEntity artifact, byte[] bytes) {
        if (bytes == null || artifact.getSizeBytes() < 0 || artifact.getSizeBytes() > MAX_ARTIFACT_BYTES
                || bytes.length > MAX_ARTIFACT_BYTES || artifact.getSha256() == null
                || artifact.getSha256().length() != 64 || bytes.length != artifact.getSizeBytes()
                || !MessageDigest.isEqual(sha256(bytes).getBytes(StandardCharsets.US_ASCII),
                artifact.getSha256().getBytes(StandardCharsets.US_ASCII))) {
            throw error(HttpStatus.SERVICE_UNAVAILABLE, "SOAR_ARTIFACT_INTEGRITY_FAILED",
                    "artifact content failed integrity verification");
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private JsonNode readTree(String value) {
        if (value == null || value.isBlank()) return mapper.createObjectNode();
        try { return mapper.readTree(value); }
        catch (JsonProcessingException ignored) { return mapper.createObjectNode(); }
    }

    private JsonNode redactedTree(String value) {
        try {
            Object parsed = mapper.readValue(value == null || value.isBlank() ? "{}" : value, Object.class);
            return mapper.valueToTree(redact(parsed));
        } catch (Exception ignored) { return mapper.createObjectNode(); }
    }

    private Object redact(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> output = new LinkedHashMap<>();
            map.forEach((key, item) -> {
                String name = String.valueOf(key).toLowerCase(Locale.ROOT);
                output.put(String.valueOf(key), name.contains("secret") || name.contains("token")
                        || name.contains("password") || name.contains("authorization") || name.equals("cookie")
                        ? "[REDACTED]" : redact(item));
            });
            return output;
        }
        if (value instanceof List<?> list) return list.stream().map(this::redact).toList();
        return value;
    }

    private static String redactFreeText(String value, int max) {
        if (value == null) return "";
        String safe = value.replaceAll("(?i)(bearer\\s+)[^\\s,;]+", "$1[REDACTED]")
                .replaceAll("(?i)((?:secret|token|password|authorization|api[_-]?key)\\s*[:=]\\s*)[^\\s,;]+",
                        "$1[REDACTED]");
        return safe.length() <= max ? safe : safe.substring(0, max);
    }

    private static String nullSafe(String value) { return value == null ? "" : value; }

    private static String normalize(String value) {
        if (value == null) return null;
        String normalized = value.trim();
        return normalized.isBlank() ? null : normalized;
    }

    private static String tenant() { return TenantContext.require(); }

    private static String sha256(byte[] value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value);
            StringBuilder out = new StringBuilder(digest.length * 2);
            for (byte item : digest) out.append(String.format("%02x", item));
            return out.toString();
        } catch (Exception failure) {
            throw new IllegalStateException("SHA-256 is unavailable", failure);
        }
    }

    private static ResponseStatusException error(HttpStatus status, String code, String message) {
        return new ResponseStatusException(status, code + ": " + message);
    }
}
