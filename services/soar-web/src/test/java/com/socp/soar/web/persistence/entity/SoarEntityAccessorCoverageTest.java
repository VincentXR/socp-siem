package com.socp.soar.web.persistence.entity;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class SoarEntityAccessorCoverageTest {

    private static final Instant NOW = Instant.now();

    @Test
    void soarRunEntityRoundTripAndNewness() {
        SoarRunEntity entity = new SoarRunEntity();
        assertThat(entity.isNew()).isTrue();

        entity.setId("run-1");
        entity.setTenantId("tenant-a");
        entity.setRequestId("req-1");
        entity.setExecutionSeriesId("series-1");
        entity.setPlaybookId("pb-1");
        entity.setPlaybookVersionId("pbv-1");
        entity.setPlaybookVersionNo(3);
        entity.setDefinitionHash("hash-1");
        entity.setTriggerType("MANUAL");
        entity.setSubjectType("ALERT");
        entity.setSubjectId("alert-1");
        entity.setStatus("RUNNING");
        entity.setTemporalWorkflowId("wf-1");
        entity.setTemporalRunId("tr-1");
        entity.setInputJson("{}");
        entity.setOutputJson("{}");
        entity.setErrorCode("E_CODE");
        entity.setErrorMessage("boom");
        entity.setRequestedBy("alice");
        entity.setCreatedAt(NOW);
        entity.setStartedAt(NOW);
        entity.setCompletedAt(NOW);
        entity.setUpdatedAt(NOW);

        assertThat(entity.getId()).isEqualTo("run-1");
        assertThat(entity.getTenantId()).isEqualTo("tenant-a");
        assertThat(entity.getRequestId()).isEqualTo("req-1");
        assertThat(entity.getExecutionSeriesId()).isEqualTo("series-1");
        assertThat(entity.getPlaybookId()).isEqualTo("pb-1");
        assertThat(entity.getPlaybookVersionId()).isEqualTo("pbv-1");
        assertThat(entity.getPlaybookVersionNo()).isEqualTo(3);
        assertThat(entity.getDefinitionHash()).isEqualTo("hash-1");
        assertThat(entity.getTriggerType()).isEqualTo("MANUAL");
        assertThat(entity.getSubjectType()).isEqualTo("ALERT");
        assertThat(entity.getSubjectId()).isEqualTo("alert-1");
        assertThat(entity.getStatus()).isEqualTo("RUNNING");
        assertThat(entity.getTemporalWorkflowId()).isEqualTo("wf-1");
        assertThat(entity.getTemporalRunId()).isEqualTo("tr-1");
        assertThat(entity.getInputJson()).isEqualTo("{}");
        assertThat(entity.getOutputJson()).isEqualTo("{}");
        assertThat(entity.getErrorCode()).isEqualTo("E_CODE");
        assertThat(entity.getErrorMessage()).isEqualTo("boom");
        assertThat(entity.getRequestedBy()).isEqualTo("alice");
        assertThat(entity.getCreatedAt()).isEqualTo(NOW);
        assertThat(entity.getStartedAt()).isEqualTo(NOW);
        assertThat(entity.getCompletedAt()).isEqualTo(NOW);
        assertThat(entity.getUpdatedAt()).isEqualTo(NOW);

        entity.setRowVersion(1L);
        assertThat(entity.getRowVersion()).isEqualTo(1L);
        assertThat(entity.isNew()).isFalse();
    }

    @Test
    void soarNodeRunEntityRoundTripAndNewness() {
        SoarNodeRunEntity entity = new SoarNodeRunEntity();
        assertThat(entity.isNew()).isTrue();

        entity.setId("node-run-1");
        entity.setTenantId("tenant-a");
        entity.setRunId("run-1");
        entity.setNodeId("act");
        entity.setIterationPath("0");
        entity.setNodeType("ACTION");
        entity.setStatus("SUCCEEDED");
        entity.setInputJson("{}");
        entity.setOutputJson("{}");
        entity.setIdempotencyKey("idem-1");
        entity.setConnectionId("conn-1");
        entity.setConnectionRevision(2);
        entity.setErrorCode("E_CODE");
        entity.setErrorMessage("boom");
        entity.setStartedAt(NOW);
        entity.setCompletedAt(NOW);
        entity.setUpdatedAt(NOW);

        assertThat(entity.getId()).isEqualTo("node-run-1");
        assertThat(entity.getTenantId()).isEqualTo("tenant-a");
        assertThat(entity.getRunId()).isEqualTo("run-1");
        assertThat(entity.getNodeId()).isEqualTo("act");
        assertThat(entity.getIterationPath()).isEqualTo("0");
        assertThat(entity.getNodeType()).isEqualTo("ACTION");
        assertThat(entity.getStatus()).isEqualTo("SUCCEEDED");
        assertThat(entity.getInputJson()).isEqualTo("{}");
        assertThat(entity.getOutputJson()).isEqualTo("{}");
        assertThat(entity.getIdempotencyKey()).isEqualTo("idem-1");
        assertThat(entity.getConnectionId()).isEqualTo("conn-1");
        assertThat(entity.getConnectionRevision()).isEqualTo(2);
        assertThat(entity.getErrorCode()).isEqualTo("E_CODE");
        assertThat(entity.getErrorMessage()).isEqualTo("boom");
        assertThat(entity.getStartedAt()).isEqualTo(NOW);
        assertThat(entity.getCompletedAt()).isEqualTo(NOW);
        assertThat(entity.getUpdatedAt()).isEqualTo(NOW);

        entity.setRowVersion(1L);
        assertThat(entity.getRowVersion()).isEqualTo(1L);
        assertThat(entity.isNew()).isFalse();
    }

    @Test
    void soarActionAttemptEntityRoundTrip() {
        SoarActionAttemptEntity entity = new SoarActionAttemptEntity();

        entity.setId("attempt-1");
        entity.setTenantId("tenant-a");
        entity.setNodeRunId("node-run-1");
        entity.setAttemptNo(2);
        entity.setStatus("FAILED");
        entity.setRequestHash("hash-1");
        entity.setRemoteOperationId("remote-1");
        entity.setRemoteTime(NOW);
        entity.setConnectionId("conn-1");
        entity.setConnectionRevision(7);
        entity.setReceiptJson("{}");
        entity.setErrorCode("E_CODE");
        entity.setErrorMessage("boom");
        entity.setRetryable(true);
        entity.setStartedAt(NOW);
        entity.setCompletedAt(NOW);
        entity.setCreatedAt(NOW);

        assertThat(entity.getId()).isEqualTo("attempt-1");
        assertThat(entity.getTenantId()).isEqualTo("tenant-a");
        assertThat(entity.getNodeRunId()).isEqualTo("node-run-1");
        assertThat(entity.getAttemptNo()).isEqualTo(2);
        assertThat(entity.getStatus()).isEqualTo("FAILED");
        assertThat(entity.getRequestHash()).isEqualTo("hash-1");
        assertThat(entity.getRemoteOperationId()).isEqualTo("remote-1");
        assertThat(entity.getRemoteTime()).isEqualTo(NOW);
        assertThat(entity.getConnectionId()).isEqualTo("conn-1");
        assertThat(entity.getConnectionRevision()).isEqualTo(7);
        assertThat(entity.getReceiptJson()).isEqualTo("{}");
        assertThat(entity.getErrorCode()).isEqualTo("E_CODE");
        assertThat(entity.getErrorMessage()).isEqualTo("boom");
        assertThat(entity.isRetryable()).isTrue();
        assertThat(entity.getStartedAt()).isEqualTo(NOW);
        assertThat(entity.getCompletedAt()).isEqualTo(NOW);
        assertThat(entity.getCreatedAt()).isEqualTo(NOW);
    }

    @Test
    void soarPlaybookEntityRoundTripAndNewness() {
        SoarPlaybookEntity entity = new SoarPlaybookEntity();
        assertThat(entity.isNew()).isTrue();

        entity.setId("pb-1");
        entity.setTenantId("tenant-a");
        entity.setName("Contain host");
        entity.setDescription("desc");
        entity.setOwner("alice");
        entity.setTagsJson("[]");
        entity.setStatus("ACTIVE");
        entity.setLatestPublishedVersion(2);
        entity.setCreatedAt(NOW);
        entity.setUpdatedAt(NOW);

        assertThat(entity.getId()).isEqualTo("pb-1");
        assertThat(entity.getTenantId()).isEqualTo("tenant-a");
        assertThat(entity.getName()).isEqualTo("Contain host");
        assertThat(entity.getDescription()).isEqualTo("desc");
        assertThat(entity.getOwner()).isEqualTo("alice");
        assertThat(entity.getTagsJson()).isEqualTo("[]");
        assertThat(entity.getStatus()).isEqualTo("ACTIVE");
        assertThat(entity.getLatestPublishedVersion()).isEqualTo(2);
        assertThat(entity.getCreatedAt()).isEqualTo(NOW);
        assertThat(entity.getUpdatedAt()).isEqualTo(NOW);

        entity.setRowVersion(1L);
        assertThat(entity.getRowVersion()).isEqualTo(1L);
        assertThat(entity.isNew()).isFalse();
    }

    @Test
    void playbookVersionEntityRoundTripAndNewness() {
        PlaybookVersionEntity entity = new PlaybookVersionEntity();
        assertThat(entity.isNew()).isTrue();

        entity.setId("pbv-1");
        entity.setTenantId("tenant-a");
        entity.setPlaybookId("pb-1");
        entity.setVersionNo(1);
        entity.setStatus("PUBLISHED");
        entity.setSchemaVersion("soar.playbook/v2");
        entity.setDefinitionJson("{}");
        entity.setLayoutJson("{}");
        entity.setDefinitionHash("hash-1");
        entity.setRiskSummaryJson("{}");
        entity.setCreatedBy("alice");
        entity.setPublishedBy("bob");
        entity.setCreatedAt(NOW);
        entity.setPublishedAt(NOW);
        entity.setUpdatedAt(NOW);

        assertThat(entity.getId()).isEqualTo("pbv-1");
        assertThat(entity.getTenantId()).isEqualTo("tenant-a");
        assertThat(entity.getPlaybookId()).isEqualTo("pb-1");
        assertThat(entity.getVersionNo()).isEqualTo(1);
        assertThat(entity.getStatus()).isEqualTo("PUBLISHED");
        assertThat(entity.getSchemaVersion()).isEqualTo("soar.playbook/v2");
        assertThat(entity.getDefinitionJson()).isEqualTo("{}");
        assertThat(entity.getLayoutJson()).isEqualTo("{}");
        assertThat(entity.getDefinitionHash()).isEqualTo("hash-1");
        assertThat(entity.getRiskSummaryJson()).isEqualTo("{}");
        assertThat(entity.getCreatedBy()).isEqualTo("alice");
        assertThat(entity.getPublishedBy()).isEqualTo("bob");
        assertThat(entity.getCreatedAt()).isEqualTo(NOW);
        assertThat(entity.getPublishedAt()).isEqualTo(NOW);
        assertThat(entity.getUpdatedAt()).isEqualTo(NOW);

        entity.setRowVersion(1L);
        assertThat(entity.getRowVersion()).isEqualTo(1L);
        assertThat(entity.isNew()).isFalse();
    }

    @Test
    void soarApprovalEntityRoundTripAndClampedRequiredApprovals() {
        SoarApprovalEntity entity = new SoarApprovalEntity();

        entity.setId("appr-1");
        entity.setTenantId("tenant-a");
        entity.setRunId("run-1");
        entity.setApprovalKey("preflight");
        entity.setNodeRunId("node-run-1");
        entity.setActionRef("endpoint/isolate-host");
        entity.setInputHash("hash-1");
        entity.setTargetSnapshotJson("{}");
        entity.setPolicyJson("{}");
        entity.setStatus("PENDING");
        entity.setRequestedBy("alice");
        entity.setApprover("bob");
        entity.setReason("containment");
        entity.setDecisionReason("approved");
        entity.setCreatedAt(NOW);
        entity.setExpiresAt(NOW);
        entity.setDecidedAt(NOW);

        assertThat(entity.getId()).isEqualTo("appr-1");
        assertThat(entity.getTenantId()).isEqualTo("tenant-a");
        assertThat(entity.getRunId()).isEqualTo("run-1");
        assertThat(entity.getApprovalKey()).isEqualTo("preflight");
        assertThat(entity.getNodeRunId()).isEqualTo("node-run-1");
        assertThat(entity.getActionRef()).isEqualTo("endpoint/isolate-host");
        assertThat(entity.getInputHash()).isEqualTo("hash-1");
        assertThat(entity.getTargetSnapshotJson()).isEqualTo("{}");
        assertThat(entity.getPolicyJson()).isEqualTo("{}");
        assertThat(entity.getStatus()).isEqualTo("PENDING");
        assertThat(entity.getRequestedBy()).isEqualTo("alice");
        assertThat(entity.getApprover()).isEqualTo("bob");
        assertThat(entity.getReason()).isEqualTo("containment");
        assertThat(entity.getDecisionReason()).isEqualTo("approved");
        assertThat(entity.getCreatedAt()).isEqualTo(NOW);
        assertThat(entity.getExpiresAt()).isEqualTo(NOW);
        assertThat(entity.getDecidedAt()).isEqualTo(NOW);

        entity.setRequiredApprovals(4);
        assertThat(entity.getRequiredApprovals()).isEqualTo(4);
        entity.setRequiredApprovals(0);
        assertThat(entity.getRequiredApprovals()).isEqualTo(1);
    }

    @Test
    void soarManualTaskEntityRoundTripAndNewness() {
        SoarManualTaskEntity entity = new SoarManualTaskEntity();
        assertThat(entity.isNew()).isTrue();

        entity.setId("task-1");
        entity.setTenantId("tenant-a");
        entity.setRunId("run-1");
        entity.setNodeId("gate");
        entity.setFormSchemaJson("{}");
        entity.setInputJson("{}");
        entity.setAssignee("alice");
        entity.setStatus("OPEN");
        entity.setDueAt(NOW);
        entity.setCompletedBy("bob");
        entity.setCompletedAt(NOW);
        entity.setCreatedAt(NOW);
        entity.setUpdatedAt(NOW);

        assertThat(entity.getId()).isEqualTo("task-1");
        assertThat(entity.getTenantId()).isEqualTo("tenant-a");
        assertThat(entity.getRunId()).isEqualTo("run-1");
        assertThat(entity.getNodeId()).isEqualTo("gate");
        assertThat(entity.getFormSchemaJson()).isEqualTo("{}");
        assertThat(entity.getInputJson()).isEqualTo("{}");
        assertThat(entity.getAssignee()).isEqualTo("alice");
        assertThat(entity.getStatus()).isEqualTo("OPEN");
        assertThat(entity.getDueAt()).isEqualTo(NOW);
        assertThat(entity.getCompletedBy()).isEqualTo("bob");
        assertThat(entity.getCompletedAt()).isEqualTo(NOW);
        assertThat(entity.getCreatedAt()).isEqualTo(NOW);
        assertThat(entity.getUpdatedAt()).isEqualTo(NOW);

        entity.setRowVersion(1L);
        assertThat(entity.getRowVersion()).isEqualTo(1L);
        assertThat(entity.isNew()).isFalse();
    }

    @Test
    void soarDispatchOutboxEntityRoundTripAndNewness() {
        SoarDispatchOutboxEntity entity = new SoarDispatchOutboxEntity();
        assertThat(entity.isNew()).isTrue();

        entity.setId("outbox-1");
        entity.setTenantId("tenant-a");
        entity.setRunId("run-1");
        entity.setStatus("PENDING");
        entity.setAttempts(2);
        entity.setNextAttemptAt(NOW);
        entity.setClaimedBy("worker-1");
        entity.setClaimedAt(NOW);
        entity.setLastError("boom");
        entity.setCreatedAt(NOW);
        entity.setUpdatedAt(NOW);

        assertThat(entity.getId()).isEqualTo("outbox-1");
        assertThat(entity.getTenantId()).isEqualTo("tenant-a");
        assertThat(entity.getRunId()).isEqualTo("run-1");
        assertThat(entity.getStatus()).isEqualTo("PENDING");
        assertThat(entity.getAttempts()).isEqualTo(2);
        assertThat(entity.getNextAttemptAt()).isEqualTo(NOW);
        assertThat(entity.getClaimedBy()).isEqualTo("worker-1");
        assertThat(entity.getClaimedAt()).isEqualTo(NOW);
        assertThat(entity.getLastError()).isEqualTo("boom");
        assertThat(entity.getCreatedAt()).isEqualTo(NOW);
        assertThat(entity.getUpdatedAt()).isEqualTo(NOW);

        entity.setRowVersion(1L);
        assertThat(entity.getRowVersion()).isEqualTo(1L);
        assertThat(entity.isNew()).isFalse();
    }

    @Test
    void soarSignalOutboxEntityRoundTripNewnessAndNullSignalKey() {
        SoarSignalOutboxEntity entity = new SoarSignalOutboxEntity();
        assertThat(entity.isNew()).isTrue();
        assertThat(entity.getSignalKey()).isEmpty();

        entity.setId("signal-1");
        entity.setTenantId("tenant-a");
        entity.setRunId("run-1");
        entity.setSignalType("APPROVAL_DECISION");
        entity.setSignalKey("gate");
        entity.setPayloadJson("{}");
        entity.setStatus("PENDING");
        entity.setAttempts(1);
        entity.setNextAttemptAt(NOW);
        entity.setClaimedBy("worker-1");
        entity.setClaimedAt(NOW);
        entity.setLastError("boom");
        entity.setCreatedAt(NOW);
        entity.setUpdatedAt(NOW);

        assertThat(entity.getId()).isEqualTo("signal-1");
        assertThat(entity.getTenantId()).isEqualTo("tenant-a");
        assertThat(entity.getRunId()).isEqualTo("run-1");
        assertThat(entity.getSignalType()).isEqualTo("APPROVAL_DECISION");
        assertThat(entity.getSignalKey()).isEqualTo("gate");
        assertThat(entity.getPayloadJson()).isEqualTo("{}");
        assertThat(entity.getStatus()).isEqualTo("PENDING");
        assertThat(entity.getAttempts()).isEqualTo(1);
        assertThat(entity.getNextAttemptAt()).isEqualTo(NOW);
        assertThat(entity.getClaimedBy()).isEqualTo("worker-1");
        assertThat(entity.getClaimedAt()).isEqualTo(NOW);
        assertThat(entity.getLastError()).isEqualTo("boom");
        assertThat(entity.getCreatedAt()).isEqualTo(NOW);
        assertThat(entity.getUpdatedAt()).isEqualTo(NOW);

        entity.setSignalKey(null);
        assertThat(entity.getSignalKey()).isEmpty();

        entity.setRowVersion(1L);
        assertThat(entity.getRowVersion()).isEqualTo(1L);
        assertThat(entity.isNew()).isFalse();
    }
}
