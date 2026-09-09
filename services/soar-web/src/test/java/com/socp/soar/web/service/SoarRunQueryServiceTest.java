package com.socp.soar.web.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.socp.platform.tenant.context.TenantContext;
import com.socp.soar.web.artifact.SoarArtifactStore;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SoarRunQueryServiceTest {

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void executionQueriesAlwaysUseTheAuthenticatedTenant() {
        SoarRunRepository runs = mock(SoarRunRepository.class);
        when(runs.searchByTenant(eq("tenant-a"), isNull(), isNull(), isNull(), isNull(),
                isNull(), isNull(), any())).thenReturn(new PageImpl<>(List.of()));
        SoarRunQueryService query = new SoarRunQueryService(runs,
                mock(SoarDispatchOutboxRepository.class), mock(SoarNodeRunRepository.class),
                mock(SoarRunEventRepository.class), mock(SoarActionAttemptRepository.class),
                mock(SoarManualTaskRepository.class), mock(SoarApprovalRepository.class),
                mock(SoarSignalOutboxRepository.class), new ObjectMapper());
        TenantContext.set("tenant-a");

        assertEquals(0, query.listRuns(PageRequest.of(0, 20), null, null, null, null, null, null)
                .getTotalElements());

        verify(runs).searchByTenant(eq("tenant-a"), isNull(), isNull(), isNull(), isNull(),
                isNull(), isNull(), any());
    }

    @Test
    void projectsTenantScopedRunsEvidenceApprovalsAndBacklog() throws Exception {
        String tenant = "tenant-a";
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        PageRequest page = PageRequest.of(0, 20);

        SoarRunEntity run = new SoarRunEntity();
        run.setId("run-1"); run.setRequestId("request-1"); run.setExecutionSeriesId("series-1");
        run.setPlaybookId("playbook-1"); run.setPlaybookVersionId("version-1"); run.setPlaybookVersionNo(3);
        run.setDefinitionHash("hash"); run.setTriggerType("ALERT"); run.setSubjectType("ALERT");
        run.setSubjectId("alert-1"); run.setStatus("RUNNING"); run.setTemporalWorkflowId("wf-1");
        run.setTemporalRunId("wf-run-1"); run.setErrorCode("E_TEST"); run.setErrorMessage("token=secret");
        run.setRequestedBy("analyst"); run.setCreatedAt(now); run.setStartedAt(now);
        run.setCompletedAt(now); run.setUpdatedAt(now); run.setExecutionNodeCount(2);

        SoarNodeRunEntity node = new SoarNodeRunEntity();
        node.setId("node-run-1"); node.setRunId("run-1"); node.setNodeId("node-1");
        node.setIterationPath("0"); node.setNodeType("ACTION"); node.setStatus("SUCCEEDED");
        node.setInputJson("{\"token\":\"secret\"}"); node.setOutputJson("{\"ok\":true}");
        node.setIdempotencyKey("idem"); node.setConnectionId("conn"); node.setConnectionRevision(1);
        node.setErrorCode("E_NODE"); node.setErrorMessage("bearer abc"); node.setStartedAt(now);
        node.setCompletedAt(now); node.setUpdatedAt(now);

        SoarRunEventEntity event = new SoarRunEventEntity();
        event.setId("event-1"); event.setRunId("run-1"); event.setNodeRunId("node-run-1");
        event.setSequenceNo(1); event.setEventType("NODE_COMPLETED"); event.setActor("system");
        event.setSummary("completed"); event.setDetailJson("{\"password\":\"secret\"}");
        event.setTraceId("trace-1"); event.setCreatedAt(now);

        SoarActionAttemptEntity attempt = new SoarActionAttemptEntity();
        attempt.setId("attempt-1"); attempt.setNodeRunId("node-run-1"); attempt.setAttemptNo(1);
        attempt.setStatus("SUCCEEDED"); attempt.setRequestHash("request-hash");
        attempt.setRemoteOperationId("remote-1"); attempt.setRemoteTime(now); attempt.setConnectionId("conn");
        attempt.setConnectionRevision(1); attempt.setReceiptJson("{\"authorization\":\"secret\"}");
        attempt.setErrorCode("E_ATTEMPT"); attempt.setErrorMessage("api_key=secret");
        attempt.setRetryable(false); attempt.setStartedAt(now); attempt.setCompletedAt(now);

        SoarManualTaskEntity manual = new SoarManualTaskEntity();
        manual.setId("task-1"); manual.setRunId("run-1"); manual.setNodeId("node-1");
        manual.setFormSchemaJson("{\"type\":\"object\"}"); manual.setInputJson("{\"cookie\":\"secret\"}");
        manual.setAssignee("analyst"); manual.setStatus("PENDING"); manual.setDueAt(now);
        manual.setCompletedBy("operator"); manual.setCompletedAt(now); manual.setCreatedAt(now);

        SoarArtifactEntity artifact = new SoarArtifactEntity();
        artifact.setId("artifact-1"); artifact.setRunId("run-1"); artifact.setNodeRunId("node-run-1");
        artifact.setMediaType("application/json"); artifact.setSizeBytes(7); artifact.setSha256(sha256("content"));
        artifact.setStorageRef("store://artifact-1"); artifact.setClassification("INTERNAL");
        artifact.setCreatedAt(now);

        SoarApprovalEntity approval = new SoarApprovalEntity();
        approval.setId("approval-1"); approval.setRunId("run-1"); approval.setApprovalKey("gate-1");
        approval.setNodeRunId("node-run-1"); approval.setActionRef("action-1"); approval.setInputHash("input-hash");
        approval.setTargetSnapshotJson("{\"token\":\"secret\"}"); approval.setPolicyJson("{\"required\":true}");
        approval.setRequiredApprovals(1); approval.setStatus("APPROVED"); approval.setRequestedBy("analyst");
        approval.setApprover("approver"); approval.setReason("review"); approval.setDecisionReason("approved");
        approval.setCreatedAt(now); approval.setExpiresAt(now.plusSeconds(60)); approval.setDecidedAt(now);

        SoarApprovalDecisionEntity vote = new SoarApprovalDecisionEntity();
        vote.setId("vote-1"); vote.setApprovalId("approval-1"); vote.setActorId("approver");
        vote.setDecision("APPROVE"); vote.setReason("looks good"); vote.setCreatedAt(now);

        SoarDispatchOutboxEntity dispatch = new SoarDispatchOutboxEntity();
        dispatch.setId("dispatch-1"); dispatch.setRunId("run-1"); dispatch.setStatus("DEAD");
        dispatch.setAttempts(3); dispatch.setLastError("token=secret"); dispatch.setUpdatedAt(now);
        SoarSignalOutboxEntity signal = new SoarSignalOutboxEntity();
        signal.setId("signal-1"); signal.setRunId("run-1"); signal.setSignalType("APPROVAL");
        signal.setSignalKey("gate-1"); signal.setStatus("DEAD"); signal.setAttempts(2);
        signal.setLastError("authorization=secret"); signal.setUpdatedAt(now);

        SoarRunRepository runs = mock(SoarRunRepository.class);
        SoarNodeRunRepository nodes = mock(SoarNodeRunRepository.class);
        SoarRunEventRepository events = mock(SoarRunEventRepository.class);
        SoarActionAttemptRepository attempts = mock(SoarActionAttemptRepository.class);
        SoarManualTaskRepository manualTasks = mock(SoarManualTaskRepository.class);
        SoarApprovalRepository approvals = mock(SoarApprovalRepository.class);
        SoarApprovalDecisionRepository decisions = mock(SoarApprovalDecisionRepository.class);
        SoarArtifactRepository artifacts = mock(SoarArtifactRepository.class);
        SoarDispatchOutboxRepository dispatches = mock(SoarDispatchOutboxRepository.class);
        SoarSignalOutboxRepository signals = mock(SoarSignalOutboxRepository.class);
        SoarArtifactStore artifactStore = mock(SoarArtifactStore.class);

        when(runs.searchByTenant(eq(tenant), any(), any(), any(), any(), any(), any(), eq(page)))
                .thenReturn(new PageImpl<>(List.of(run)));
        when(runs.findByTenantIdAndId(tenant, "run-1")).thenReturn(Optional.of(run));
        when(runs.countByTenantIdAndStatus(tenant, "RUNNING")).thenReturn(2L);
        when(nodes.findByTenantIdAndRunIdOrderByUpdatedAtAsc(tenant, "run-1")).thenReturn(List.of(node));
        when(nodes.findByTenantIdAndRunIdOrderByUpdatedAtAsc(tenant, "run-1", page))
                .thenReturn(new PageImpl<>(List.of(node), page, 1));
        when(nodes.findByTenantIdAndId(tenant, "node-run-1")).thenReturn(Optional.of(node));
        when(events.findByTenantIdAndRunIdOrderBySequenceNoAsc(tenant, "run-1")).thenReturn(List.of(event));
        when(events.findByTenantIdAndRunIdAndSequenceNoGreaterThanOrderBySequenceNoAsc(tenant, "run-1", 0, page))
                .thenReturn(new PageImpl<>(List.of(event), page, 1));
        when(attempts.findByTenantIdAndNodeRunIdOrderByAttemptNoAsc(tenant, "node-run-1", page))
                .thenReturn(new PageImpl<>(List.of(attempt), page, 1));
        when(manualTasks.findByTenantIdAndStatusOrderByDueAtAsc(tenant, "PENDING")).thenReturn(List.of(manual));
        when(manualTasks.findByTenantIdOrderByCreatedAtDesc(tenant)).thenReturn(List.of(manual));
        when(manualTasks.findByTenantIdOrderByCreatedAtDesc(tenant, page)).thenReturn(new PageImpl<>(List.of(manual), page, 1));
        when(artifacts.findByTenantIdAndRunIdOrderByCreatedAtAsc(tenant, "run-1")).thenReturn(List.of(artifact));
        when(artifacts.findByTenantIdAndRunIdOrderByCreatedAtAsc(tenant, "run-1", page))
                .thenReturn(new PageImpl<>(List.of(artifact), page, 1));
        when(artifacts.findByTenantIdAndId(tenant, "artifact-1")).thenReturn(Optional.of(artifact));
        when(approvals.findByTenantIdOrderByCreatedAtDesc(tenant)).thenReturn(List.of(approval));
        when(approvals.findByTenantIdOrderByCreatedAtDesc(tenant, page)).thenReturn(new PageImpl<>(List.of(approval), page, 1));
        when(decisions.findByTenantIdAndApprovalIdOrderByCreatedAtAsc(tenant, "approval-1")).thenReturn(List.of(vote));
        when(dispatches.countByTenantIdAndStatusAndNextAttemptAtLessThanEqual(eq(tenant), eq("PENDING"), any()))
                .thenReturn(3L);
        when(dispatches.countByStatus("PENDING")).thenReturn(3L);
        when(dispatches.countByStatus("DEAD")).thenReturn(1L);
        when(dispatches.findByTenantIdAndStatusOrderByUpdatedAtAsc(tenant, "DEAD")).thenReturn(List.of(dispatch));
        when(signals.countByTenantIdAndStatus(tenant, "PENDING")).thenReturn(4L);
        when(signals.countByStatus("PENDING")).thenReturn(4L);
        when(signals.countByStatus("DEAD")).thenReturn(1L);
        when(signals.findByTenantIdAndStatusOrderByUpdatedAtAsc(tenant, "DEAD")).thenReturn(List.of(signal));
        when(artifactStore.read("store://artifact-1"))
                .thenReturn(Optional.of("content".getBytes(StandardCharsets.UTF_8)));

        SoarRunQueryService query = new SoarRunQueryService(runs, dispatches, nodes, events, attempts,
                manualTasks, approvals, signals, new ObjectMapper());
        query.setArtifacts(artifacts);
        query.setApprovalDecisions(decisions);
        query.setArtifactStore(artifactStore);
        TenantContext.set(tenant);

        assertEquals(1, query.listRuns(page, " ", " ", " ", " ", null, null).getTotalElements());
        assertEquals("run-1", query.getRun("run-1").get("runId"));
        assertEquals(1, query.listNodes("run-1").size());
        assertEquals(1, query.listNodes("run-1", page).getTotalElements());
        assertEquals(1, query.listArtifacts("run-1").size());
        assertEquals(1, query.listArtifacts("run-1", page).getTotalElements());
        assertEquals("content", query.getArtifactContent("artifact-1"));
        assertEquals(1, query.listNodeAttempts("node-run-1", page).getTotalElements());
        assertEquals(1, query.listEvents("run-1").size());
        assertEquals(1, query.listEvents("run-1", -1, page).getTotalElements());
        assertEquals(1, query.listManualTasks(true).size());
        assertEquals(1, query.listManualTasks(false).size());
        assertEquals(1, query.listManualTasks(true, page).getTotalElements());
        assertEquals(1, query.listManualTasks(false, page).getTotalElements());
        Map<?, ?> runsByStatus = (Map<?, ?>) query.stats().get("runsByStatus");
        assertEquals(2L, runsByStatus.get("RUNNING"));
        assertEquals(4L, query.healthBacklog().get("signalBacklog"));
        assertEquals(2, query.deadDispatches().size());
        assertEquals(1, query.listApprovals().size());
        assertEquals(1, query.listApprovals(page).getTotalElements());
        assertEquals(1L, query.listApprovals().get(0).get("approvedVotes"));
    }

    private static String sha256(String value) throws Exception {
        StringBuilder result = new StringBuilder();
        for (byte item : MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8))) {
            result.append(String.format("%02x", item));
        }
        return result.toString();
    }
}
