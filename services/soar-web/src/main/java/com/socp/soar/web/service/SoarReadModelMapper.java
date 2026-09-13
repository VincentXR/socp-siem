package com.socp.soar.web.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.socp.soar.web.domain.SoarPlaybookVersionStatus;
import com.socp.soar.web.persistence.entity.SoarActionAttemptEntity;
import com.socp.soar.web.persistence.entity.SoarApprovalDecisionEntity;
import com.socp.soar.web.persistence.entity.SoarApprovalEntity;
import com.socp.soar.web.persistence.entity.SoarArtifactEntity;
import com.socp.soar.web.persistence.entity.SoarManualTaskEntity;
import com.socp.soar.web.persistence.entity.SoarNodeRunEntity;
import com.socp.soar.web.persistence.entity.SoarPlaybookEntity;
import com.socp.soar.web.persistence.entity.SoarRunEntity;
import com.socp.soar.web.persistence.entity.SoarRunEventEntity;
import com.socp.soar.web.persistence.entity.PlaybookVersionEntity;
import com.socp.soar.web.persistence.repository.PlaybookVersionRepository;
import com.socp.soar.web.persistence.repository.SoarApprovalDecisionRepository;
import com.socp.soar.web.persistence.repository.SoarPlaybookRepository;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the public SOAR read models.  It intentionally contains no command
 * or state-transition logic; the application service only chooses the
 * tenant-scoped repository query and delegates serialization here.
 */
final class SoarReadModelMapper {

    private final PlaybookVersionRepository versions;
    private final SoarPlaybookRepository playbooks;
    private final ObjectMapper mapper;
    private SoarApprovalDecisionRepository approvalDecisions;

    SoarReadModelMapper(PlaybookVersionRepository versions, SoarPlaybookRepository playbooks,
                        ObjectMapper mapper) {
        this.versions = versions;
        this.playbooks = playbooks;
        this.mapper = mapper;
    }

    void setApprovalDecisions(SoarApprovalDecisionRepository approvalDecisions) {
        this.approvalDecisions = approvalDecisions;
    }

    Map<String, Object> attemptView(SoarActionAttemptEntity attempt) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", attempt.getId());
        result.put("nodeRunId", attempt.getNodeRunId());
        result.put("attemptNo", attempt.getAttemptNo());
        result.put("status", attempt.getStatus());
        result.put("requestHash", attempt.getRequestHash());
        result.put("remoteOperationId", attempt.getRemoteOperationId());
        result.put("connectionId", nullSafe(attempt.getConnectionId()));
        result.put("connectionRevision", attempt.getConnectionRevision());
        result.put("remoteTime", attempt.getRemoteTime());
        result.put("receipt", redactedTree(attempt.getReceiptJson()));
        result.put("errorCode", attempt.getErrorCode());
        result.put("errorMessage", redactFreeText(attempt.getErrorMessage(), 2048));
        result.put("retryable", attempt.isRetryable());
        result.put("startedAt", attempt.getStartedAt());
        result.put("completedAt", attempt.getCompletedAt());
        return result;
    }

    Map<String, Object> manualTaskView(SoarManualTaskEntity task) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", task.getId());
        result.put("runId", task.getRunId());
        result.put("nodeId", task.getNodeId());
        result.put("formSchema", readTree(task.getFormSchemaJson()));
        result.put("input", redactedTree(task.getInputJson()));
        result.put("assignee", nullSafe(task.getAssignee()));
        result.put("status", task.getStatus());
        result.put("dueAt", task.getDueAt());
        result.put("completedBy", nullSafe(task.getCompletedBy()));
        result.put("completedAt", task.getCompletedAt());
        result.put("createdAt", task.getCreatedAt());
        return result;
    }

    Map<String, Object> playbookView(String tenant, SoarPlaybookEntity playbook) {
        List<PlaybookVersionEntity> history = versions.findByTenantIdAndPlaybookIdOrderByVersionNoDesc(
                tenant, playbook.getId());
        PlaybookVersionEntity draft = history.stream()
                .filter(version -> SoarPlaybookVersionStatus.DRAFT.name().equals(version.getStatus()))
                .findFirst().orElse(null);
        return playbookView(playbook, draft);
    }

    Map<String, Object> playbookView(SoarPlaybookEntity playbook, PlaybookVersionEntity draft) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", playbook.getId());
        result.put("name", playbook.getName());
        result.put("description", playbook.getDescription());
        result.put("owner", playbook.getOwner());
        result.put("tags", readList(playbook.getTagsJson()));
        result.put("status", playbook.getStatus());
        result.put("latestPublishedVersion", playbook.getLatestPublishedVersion());
        result.put("draftVersion", draft == null ? null : draft.getVersionNo());
        result.put("createdAt", playbook.getCreatedAt());
        result.put("updatedAt", playbook.getUpdatedAt());
        return result;
    }

    Map<String, Object> versionView(String tenant, PlaybookVersionEntity version) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", version.getId());
        result.put("playbookId", version.getPlaybookId());
        result.put("version", version.getVersionNo());
        result.put("status", version.getStatus());
        result.put("playbookStatus", playbooks.findByTenantIdAndId(tenant, version.getPlaybookId())
                .map(SoarPlaybookEntity::getStatus).orElse("UNKNOWN"));
        result.put("schemaVersion", version.getSchemaVersion());
        result.put("definition", readTree(version.getDefinitionJson()));
        result.put("layout", readTree(version.getLayoutJson()));
        result.put("definitionHash", version.getDefinitionHash());
        result.put("riskSummary", readTree(version.getRiskSummaryJson()));
        result.put("createdBy", version.getCreatedBy());
        result.put("publishedBy", version.getPublishedBy());
        result.put("rowVersion", version.getRowVersion());
        result.put("createdAt", version.getCreatedAt());
        result.put("publishedAt", version.getPublishedAt());
        result.put("updatedAt", version.getUpdatedAt());
        return result;
    }

    Map<String, Object> runView(SoarRunEntity run) {
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

    Map<String, Object> nodeView(SoarNodeRunEntity node) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", node.getId());
        result.put("runId", node.getRunId());
        result.put("nodeId", node.getNodeId());
        result.put("iterationPath", node.getIterationPath());
        result.put("nodeType", node.getNodeType());
        result.put("status", node.getStatus());
        result.put("input", redactedTree(node.getInputJson()));
        result.put("output", redactedTree(node.getOutputJson()));
        result.put("idempotencyKey", nullSafe(node.getIdempotencyKey()));
        result.put("connectionId", nullSafe(node.getConnectionId()));
        result.put("connectionRevision", node.getConnectionRevision());
        result.put("errorCode", nullSafe(node.getErrorCode()));
        result.put("errorMessage", redactFreeText(node.getErrorMessage(), 2048));
        result.put("startedAt", node.getStartedAt());
        result.put("completedAt", node.getCompletedAt());
        result.put("updatedAt", node.getUpdatedAt());
        return result;
    }

    Map<String, Object> artifactView(SoarArtifactEntity artifact) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", artifact.getId());
        result.put("runId", artifact.getRunId());
        result.put("nodeRunId", nullSafe(artifact.getNodeRunId()));
        result.put("mediaType", artifact.getMediaType());
        result.put("sizeBytes", artifact.getSizeBytes());
        result.put("sha256", artifact.getSha256());
        result.put("storageRef", artifact.getStorageRef());
        result.put("classification", artifact.getClassification());
        result.put("expiresAt", artifact.getExpiresAt());
        result.put("createdAt", artifact.getCreatedAt());
        return result;
    }

    Map<String, Object> eventView(SoarRunEventEntity event) {
        return Map.of("id", event.getId(), "runId", event.getRunId(), "nodeRunId", nullSafe(event.getNodeRunId()),
                "sequence", event.getSequenceNo(), "eventType", event.getEventType(),
                "actor", nullSafe(event.getActor()), "summary", event.getSummary(),
                "detail", redactedTree(event.getDetailJson()), "traceId", nullSafe(event.getTraceId()),
                "createdAt", event.getCreatedAt());
    }

    Map<String, Object> approvalView(String tenant, SoarApprovalEntity approval) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", approval.getId());
        result.put("runId", approval.getRunId());
        result.put("approvalKey", nullSafe(approval.getApprovalKey()));
        result.put("nodeRunId", nullSafe(approval.getNodeRunId()));
        result.put("actionRef", nullSafe(approval.getActionRef()));
        result.put("inputHash", nullSafe(approval.getInputHash()));
        result.put("targetSnapshot", redactedTree(approval.getTargetSnapshotJson()));
        result.put("approvalPolicy", redactedTree(approval.getPolicyJson()));
        result.put("requiredApprovals", approval.getRequiredApprovals());
        List<Map<String, Object>> voteViews = approvalVotes(tenant, approval);
        long approvedVotes = voteViews.stream()
                .filter(vote -> "APPROVE".equals(vote.get("decision"))).count();
        if (approvedVotes == 0 && "APPROVED".equalsIgnoreCase(approval.getStatus())) {
            approvedVotes = Math.min(1, Math.max(1, approval.getRequiredApprovals()));
        }
        result.put("approvedVotes", approvedVotes);
        result.put("decisions", voteViews);
        result.put("status", approval.getStatus());
        result.put("requestedBy", approval.getRequestedBy());
        result.put("approver", nullSafe(approval.getApprover()));
        result.put("reason", redactFreeText(approval.getReason(), 2048));
        result.put("decisionReason", redactFreeText(approval.getDecisionReason(), 2048));
        result.put("createdAt", approval.getCreatedAt());
        result.put("expiresAt", approval.getExpiresAt());
        result.put("decidedAt", approval.getDecidedAt());
        return result;
    }

    private List<Map<String, Object>> approvalVotes(String tenant, SoarApprovalEntity approval) {
        if (approvalDecisions == null || approval == null || approval.getId() == null) return List.of();
        List<SoarApprovalDecisionEntity> rows = approvalDecisions
                .findByTenantIdAndApprovalIdOrderByCreatedAtAsc(tenant, approval.getId());
        if (rows == null || rows.isEmpty()) return List.of();
        return rows.stream().map(vote -> {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("id", vote.getId());
            view.put("actor", vote.getActorId());
            view.put("decision", vote.getDecision());
            view.put("reason", redactFreeText(vote.getReason(), 2048));
            view.put("createdAt", vote.getCreatedAt());
            return view;
        }).toList();
    }

    private JsonNode readTree(String json) {
        try {
            return mapper.readTree(json == null || json.isBlank() ? "{}" : json);
        } catch (Exception ignored) {
            return mapper.createObjectNode();
        }
    }

    private JsonNode redactedTree(String json) {
        try {
            return mapper.valueToTree(redact(mapper.readValue(json == null || json.isBlank() ? "{}" : json, Object.class)));
        } catch (Exception ignored) {
            return mapper.createObjectNode();
        }
    }

    private List<String> readList(String json) {
        try {
            return mapper.readValue(json == null ? "[]" : json,
                    mapper.getTypeFactory().constructCollectionType(List.class, String.class));
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private Object redact(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> output = new LinkedHashMap<>();
            map.forEach((key, item) -> {
                String name = String.valueOf(key).toLowerCase(java.util.Locale.ROOT);
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

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }
}
