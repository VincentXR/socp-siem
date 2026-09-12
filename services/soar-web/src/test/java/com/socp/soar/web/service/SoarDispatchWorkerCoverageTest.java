package com.socp.soar.web.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.socp.platform.tenant.context.TenantContext;
import com.socp.soar.web.persistence.entity.PlaybookVersionEntity;
import com.socp.soar.web.persistence.entity.SoarDispatchOutboxEntity;
import com.socp.soar.web.persistence.entity.SoarRunEntity;
import com.socp.soar.web.persistence.repository.PlaybookVersionRepository;
import com.socp.soar.web.persistence.repository.SoarDispatchOutboxRepository;
import com.socp.soar.web.persistence.repository.SoarRunRepository;
import com.socp.soar.web.temporal.request.SoarWorkflowRequest;
import io.temporal.api.common.v1.WorkflowExecution;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class SoarDispatchWorkerCoverageTest {

    @Mock
    private SoarDispatchOutboxRepository dispatches;
    @Mock
    private SoarRunRepository runs;
    @Mock
    private PlaybookVersionRepository versions;
    @Mock
    private TemporalExecutor temporal;

    private SoarDispatchWorker worker;

    @BeforeEach
    void setUp() {
        TenantContext.set("tenant-a");
        worker = new SoarDispatchWorker(dispatches, runs, versions, temporal, new ObjectMapper());
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void claimedPendingRowStartsTemporalWithStableWorkflowId() {
        SoarDispatchOutboxEntity outbox = outbox(0, "PENDING");
        SoarRunEntity run = run("QUEUED");
        given(temporal.isAvailable()).willReturn(true);
        given(dispatches.findTop100ByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(eq("PENDING"), any()))
                .willReturn(List.of(outbox));
        given(dispatches.claim(eq("tenant-a"), eq("out-1"), anyString(), any())).willReturn(1);
        given(runs.findByTenantIdAndId("tenant-a", "run-1")).willReturn(Optional.of(run));
        given(versions.findByTenantIdAndId("tenant-a", "ver-1")).willReturn(Optional.of(version()));
        given(temporal.startWorkflow(any(SoarWorkflowRequest.class), anyString()))
                .willReturn(WorkflowExecution.newBuilder().setRunId("temporal-run-1").build());

        worker.tick();

        ArgumentCaptor<SoarWorkflowRequest> request = ArgumentCaptor.forClass(SoarWorkflowRequest.class);
        ArgumentCaptor<String> workflowId = ArgumentCaptor.forClass(String.class);
        verify(temporal).startWorkflow(request.capture(), workflowId.capture());
        assertThat(workflowId.getValue()).isEqualTo("soar-tenant-a-run-1");
        assertThat(request.getValue().tenantId()).isEqualTo("tenant-a");
        assertThat(request.getValue().runId()).isEqualTo("run-1");
        assertThat(request.getValue().resumeFromNodeId()).isEqualTo("node-7");
        assertThat(run.getTemporalWorkflowId()).isEqualTo("soar-tenant-a-run-1");
        assertThat(run.getTemporalRunId()).isEqualTo("temporal-run-1");
        assertThat(outbox.getStatus()).isEqualTo("DISPATCHED");
        assertThat(outbox.getClaimedBy()).startsWith("soar-");
        verify(dispatches).save(outbox);
    }

    @Test
    void alreadyStartedWorkflowIsTreatedAsDispatched() {
        SoarDispatchOutboxEntity outbox = outbox(2, "PENDING");
        given(temporal.isAvailable()).willReturn(true);
        given(dispatches.findTop100ByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(eq("PENDING"), any()))
                .willReturn(List.of(outbox));
        given(dispatches.claim(eq("tenant-a"), eq("out-1"), anyString(), any())).willReturn(1);
        given(runs.findByTenantIdAndId("tenant-a", "run-1")).willReturn(Optional.of(run("QUEUED")));
        given(versions.findByTenantIdAndId("tenant-a", "ver-1")).willReturn(Optional.of(version()));
        given(temporal.startWorkflow(any(SoarWorkflowRequest.class), anyString()))
                .willThrow(new RuntimeException("io.temporal.internal.sync.WorkflowExecutionAlreadyStarted: already started"));

        worker.tick();

        assertThat(outbox.getStatus()).isEqualTo("DISPATCHED");
        assertThat(outbox.getAttempts()).isEqualTo(2);
        verify(dispatches).save(outbox);
    }

    @Test
    void unclaimedRowIsLeftForAnotherWorker() {
        SoarDispatchOutboxEntity outbox = outbox(0, "PENDING");
        given(temporal.isAvailable()).willReturn(true);
        given(dispatches.findTop100ByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(eq("PENDING"), any()))
                .willReturn(List.of(outbox));
        given(dispatches.claim(eq("tenant-a"), eq("out-1"), anyString(), any())).willReturn(0);

        worker.tick();

        assertThat(outbox.getStatus()).isEqualTo("PENDING");
        verify(temporal, never()).startWorkflow(any(SoarWorkflowRequest.class), anyString());
        verify(dispatches, never()).save(any(SoarDispatchOutboxEntity.class));
    }

    @Test
    void unavailableTemporalKeepsRowQueued() {
        SoarDispatchOutboxEntity outbox = outbox(0, "PENDING");
        given(temporal.isAvailable()).willReturn(false);
        given(dispatches.findTop100ByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(eq("PENDING"), any()))
                .willReturn(List.of(outbox));

        worker.tick();

        assertThat(outbox.getStatus()).isEqualTo("PENDING");
        verify(dispatches, never()).claim(anyString(), anyString(), anyString(), any());
        verify(temporal, never()).startWorkflow(any(SoarWorkflowRequest.class), anyString());
    }

    @Test
    void holdRunAwaitingApprovalIsNotDispatched() {
        SoarDispatchOutboxEntity outbox = outbox(0, "PENDING");
        given(temporal.isAvailable()).willReturn(true);
        given(dispatches.findTop100ByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(eq("PENDING"), any()))
                .willReturn(List.of(outbox));
        given(dispatches.claim(eq("tenant-a"), eq("out-1"), anyString(), any())).willReturn(1);
        given(runs.findByTenantIdAndId("tenant-a", "run-1")).willReturn(Optional.of(run("HOLD")));

        worker.tick();

        assertThat(outbox.getStatus()).isEqualTo("CANCELLED");
        assertThat(outbox.getLastError()).contains("dispatch skipped for run status HOLD");
        verify(dispatches).save(outbox);
        verify(temporal, never()).startWorkflow(any(SoarWorkflowRequest.class), anyString());
    }

    @Test
    void cancellationWonAfterClaimIsRecheckedBeforeTemporalStart() {
        SoarDispatchOutboxEntity outbox = outbox(0, "PENDING");
        SoarRunEntity queued = run("QUEUED");
        SoarRunEntity cancelling = run("CANCELLING");
        given(temporal.isAvailable()).willReturn(true);
        given(dispatches.findTop100ByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(eq("PENDING"), any()))
                .willReturn(List.of(outbox));
        given(dispatches.claim(eq("tenant-a"), eq("out-1"), anyString(), any())).willReturn(1);
        given(runs.findByTenantIdAndId("tenant-a", "run-1"))
                .willReturn(Optional.of(queued), Optional.of(cancelling));
        given(versions.findByTenantIdAndId("tenant-a", "ver-1")).willReturn(Optional.of(version()));

        worker.tick();

        assertThat(outbox.getStatus()).isEqualTo("CANCELLED");
        assertThat(outbox.getLastError()).contains("CANCELLING");
        verify(temporal, never()).startWorkflow(any(SoarWorkflowRequest.class), anyString());
    }

    @Test
    void cancellationWonImmediatelyAfterTemporalStartIsDeliveredToStartedWorkflow() {
        SoarDispatchOutboxEntity outbox = outbox(0, "PENDING");
        SoarRunEntity queued = run("QUEUED");
        SoarRunEntity dispatching = run("DISPATCHING");
        SoarRunEntity cancelling = run("CANCELLING");
        given(temporal.isAvailable()).willReturn(true);
        given(dispatches.findTop100ByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(eq("PENDING"), any()))
                .willReturn(List.of(outbox));
        given(dispatches.claim(eq("tenant-a"), eq("out-1"), anyString(), any())).willReturn(1);
        given(runs.findByTenantIdAndId("tenant-a", "run-1"))
                .willReturn(Optional.of(queued), Optional.of(dispatching), Optional.of(cancelling));
        given(versions.findByTenantIdAndId("tenant-a", "ver-1")).willReturn(Optional.of(version()));
        given(temporal.startWorkflow(any(SoarWorkflowRequest.class), anyString()))
                .willReturn(WorkflowExecution.newBuilder().setRunId("temporal-run-late-cancel").build());

        worker.tick();

        verify(temporal).cancelWorkflow("soar-tenant-a-run-1");
        assertThat(outbox.getStatus()).isEqualTo("DISPATCHED");
    }

    @Test
    void transientFailureRequeuesAndRestoresQueuedProjection() {
        SoarDispatchOutboxEntity outbox = outbox(0, "PENDING");
        SoarRunEntity run = run("QUEUED");
        given(temporal.isAvailable()).willReturn(true);
        given(dispatches.findTop100ByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(eq("PENDING"), any()))
                .willReturn(List.of(outbox));
        given(dispatches.claim(eq("tenant-a"), eq("out-1"), anyString(), any())).willReturn(1);
        given(runs.findByTenantIdAndId("tenant-a", "run-1")).willReturn(Optional.of(run));
        given(versions.findByTenantIdAndId("tenant-a", "ver-1")).willReturn(Optional.of(version()));
        given(temporal.startWorkflow(any(SoarWorkflowRequest.class), anyString()))
                .willThrow(new IllegalStateException("temporal start timed out"));

        worker.tick();

        assertThat(outbox.getAttempts()).isEqualTo(1);
        assertThat(outbox.getStatus()).isEqualTo("PENDING");
        assertThat(outbox.getLastError()).isEqualTo("temporal start timed out");
        assertThat(outbox.getNextAttemptAt()).isAfter(Instant.now());
        assertThat(run.getStatus()).isEqualTo("QUEUED");
        verify(dispatches).save(outbox);
    }

    @Test
    void exhaustedRetryBudgetMovesOutboxAndRunToDead() {
        SoarDispatchOutboxEntity outbox = outbox(9, "PENDING");
        SoarRunEntity run = run("QUEUED");
        given(temporal.isAvailable()).willReturn(true);
        given(dispatches.findTop100ByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(eq("PENDING"), any()))
                .willReturn(List.of(outbox));
        given(dispatches.claim(eq("tenant-a"), eq("out-1"), anyString(), any())).willReturn(1);
        given(runs.findByTenantIdAndId("tenant-a", "run-1")).willReturn(Optional.of(run));
        given(versions.findByTenantIdAndId("tenant-a", "ver-1")).willReturn(Optional.of(version()));
        given(temporal.startWorkflow(any(SoarWorkflowRequest.class), anyString()))
                .willThrow(new IllegalStateException("temporal start timed out"));

        worker.tick();

        assertThat(outbox.getAttempts()).isEqualTo(10);
        assertThat(outbox.getStatus()).isEqualTo("DEAD");
        assertThat(run.getStatus()).isEqualTo("DEAD");
        assertThat(run.getErrorCode()).isEqualTo("DISPATCH_DEAD_LETTER");
        assertThat(run.getErrorMessage()).isEqualTo("Temporal dispatch exhausted retries");
    }

    @Test
    void exhaustedRetryBudgetDoesNotOverwriteAnAlreadyTerminalRun() {
        SoarDispatchOutboxEntity outbox = outbox(9, "PENDING");
        SoarRunEntity run = run("PARTIALLY_SUCCEEDED");
        given(temporal.isAvailable()).willReturn(true);
        given(dispatches.findTop100ByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(eq("PENDING"), any()))
                .willReturn(List.of(outbox));
        given(dispatches.claim(eq("tenant-a"), eq("out-1"), anyString(), any())).willReturn(1);
        given(runs.findByTenantIdAndId("tenant-a", "run-1")).willReturn(Optional.of(run));

        worker.tick();

        assertThat(outbox.getStatus()).isEqualTo("CANCELLED");
        assertThat(run.getStatus()).isEqualTo("PARTIALLY_SUCCEEDED");
        assertThat(run.getErrorCode()).isNull();
    }

    @Test
    void missingRunProjectionIsRecordedAsRetryableFailure() {
        SoarDispatchOutboxEntity outbox = outbox(0, "PENDING");
        given(temporal.isAvailable()).willReturn(true);
        given(dispatches.findTop100ByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(eq("PENDING"), any()))
                .willReturn(List.of(outbox));
        given(dispatches.claim(eq("tenant-a"), eq("out-1"), anyString(), any())).willReturn(1);
        given(runs.findByTenantIdAndId("tenant-a", "run-1")).willReturn(Optional.empty());

        worker.tick();

        assertThat(outbox.getAttempts()).isEqualTo(1);
        assertThat(outbox.getStatus()).isEqualTo("PENDING");
        assertThat(outbox.getLastError()).contains("run not found: run-1");
        verify(temporal, never()).startWorkflow(any(SoarWorkflowRequest.class), anyString());
    }

    @Test
    void missingVersionIsRecordedAsRetryableFailure() {
        SoarDispatchOutboxEntity outbox = outbox(0, "PENDING");
        given(temporal.isAvailable()).willReturn(true);
        given(dispatches.findTop100ByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(eq("PENDING"), any()))
                .willReturn(List.of(outbox));
        given(dispatches.claim(eq("tenant-a"), eq("out-1"), anyString(), any())).willReturn(1);
        given(runs.findByTenantIdAndId("tenant-a", "run-1")).willReturn(Optional.of(run("QUEUED")));
        given(versions.findByTenantIdAndId("tenant-a", "ver-1")).willReturn(Optional.empty());

        worker.tick();

        assertThat(outbox.getAttempts()).isEqualTo(1);
        assertThat(outbox.getLastError()).contains("version not found: ver-1");
        verify(temporal, never()).startWorkflow(any(SoarWorkflowRequest.class), anyString());
    }

    private static SoarDispatchOutboxEntity outbox(int attempts, String status) {
        SoarDispatchOutboxEntity outbox = new SoarDispatchOutboxEntity();
        outbox.setId("out-1");
        outbox.setTenantId("tenant-a");
        outbox.setRunId("run-1");
        outbox.setStatus(status);
        outbox.setAttempts(attempts);
        outbox.setNextAttemptAt(Instant.now().minusSeconds(1));
        outbox.setCreatedAt(Instant.now().minusSeconds(10));
        outbox.setUpdatedAt(Instant.now().minusSeconds(10));
        return outbox;
    }

    private static SoarRunEntity run(String status) {
        SoarRunEntity run = new SoarRunEntity();
        run.setId("run-1");
        run.setTenantId("tenant-a");
        run.setRequestId("req-1");
        run.setExecutionSeriesId("series-1");
        run.setPlaybookId("pb-1");
        run.setPlaybookVersionId("ver-1");
        run.setStatus(status);
        run.setInputJson("{\"_soar\":{\"resumeFromNodeId\":\"node-7\"}}");
        run.setUpdatedAt(Instant.now());
        return run;
    }

    private static PlaybookVersionEntity version() {
        PlaybookVersionEntity version = new PlaybookVersionEntity();
        version.setId("ver-1");
        version.setTenantId("tenant-a");
        version.setPlaybookId("pb-1");
        version.setDefinitionJson("{\"nodes\":[]}");
        return version;
    }
}
