package com.socp.soar.web.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the public SOAR read models.  It intentionally contains no command
 * or state-transition logic; the application service only chooses the
 * tenant-scoped repository query and delegates serialization here.
 */
final class SoarReadModelMapper {

    private final ObjectMapper mapper;

    SoarReadModelMapper(ObjectMapper mapper) { this.mapper = mapper; }

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
        result.put("errorMessage", SoarRedaction.freeText(attempt.getErrorMessage(), 2048));
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

    Map<String, Object> playbookView(SoarPlaybookEntity playbook, Integer draftVersion) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", playbook.getId());
        result.put("name", playbook.getName());
        result.put("description", playbook.getDescription());
        result.put("owner", playbook.getOwner());
        result.put("tags", readList(playbook.getTagsJson()));
        result.put("status", playbook.getStatus());
        result.put("latestPublishedVersion", playbook.getLatestPublishedVersion());
        result.put("draftVersion", draftVersion);
        result.put("createdAt", playbook.getCreatedAt());
        result.put("updatedAt", playbook.getUpdatedAt());
        return result;
    }

    Map<String, Object> versionView(PlaybookVersionEntity version, String playbookStatus) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", version.getId());
        result.put("playbookId", version.getPlaybookId());
        result.put("version", version.getVersionNo());
        result.put("status", version.getStatus());
        result.put("playbookStatus", playbookStatus);
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
        result.put("originAlarmId", run.getOriginAlarmId());
        result.put("originCaseId", run.getOriginCaseId());
        result.put("subject", Map.of("type", nullSafe(run.getSubjectType()), "id", nullSafe(run.getSubjectId())));
        result.put("status", run.getStatus());
        result.put("executionNodeCount", run.getExecutionNodeCount() == null ? 0 : run.getExecutionNodeCount());
        result.put("temporalWorkflowId", run.getTemporalWorkflowId());
        result.put("temporalRunId", run.getTemporalRunId());
        if (run.getErrorCode() != null) result.put("errorCode", run.getErrorCode());
        if (run.getErrorMessage() != null) result.put("errorMessage", SoarRedaction.freeText(run.getErrorMessage(), 2048));
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
        result.put("errorMessage", SoarRedaction.freeText(node.getErrorMessage(), 2048));
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

    Map<String, Object> approvalView(SoarApprovalEntity approval, List<SoarApprovalDecisionEntity> decisions) {
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
        List<Map<String, Object>> voteViews = approvalVotes(decisions);
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
        result.put("reason", SoarRedaction.freeText(approval.getReason(), 2048));
        result.put("decisionReason", SoarRedaction.freeText(approval.getDecisionReason(), 2048));
        result.put("createdAt", approval.getCreatedAt());
        result.put("expiresAt", approval.getExpiresAt());
        result.put("decidedAt", approval.getDecidedAt());
        return result;
    }

    private List<Map<String, Object>> approvalVotes(List<SoarApprovalDecisionEntity> rows) {
        return rows.stream().map(vote -> {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("id", vote.getId());
            view.put("actor", vote.getActorId());
            view.put("decision", vote.getDecision());
            view.put("reason", SoarRedaction.freeText(vote.getReason(), 2048));
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
            return mapper.valueToTree(SoarRedaction.structured(mapper.readValue(json == null || json.isBlank() ? "{}" : json, Object.class)));
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

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }
}
