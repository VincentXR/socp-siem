package com.socp.soar.web.service;

import com.socp.soar.web.persistence.entity.SoarRunEntity;
import com.socp.soar.web.persistence.repository.SoarRunRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SoarRunRecoveryWorkerCoverageTest {
    @Mock SoarRunRepository runs;
    @Mock TemporalExecutor temporal;
    @Mock SoarRunRecoveryState state;
    SoarRunRecoveryWorker worker;
    SoarRunEntity run;

    @BeforeEach void setup() {
        worker = new SoarRunRecoveryWorker(runs, temporal, state, 600);
        run = new SoarRunEntity();
        run.setId("run-1"); run.setTenantId("tenant-a"); run.setRowVersion(4L);
        run.setStatus("CANCELLING"); run.setTemporalWorkflowId("attached-workflow");
        run.setUpdatedAt(Instant.now().minusSeconds(1200));
    }

    void poll(List<SoarRunEntity> rows) {
        when(temporal.isAvailable()).thenReturn(true);
        when(runs.findRecoveryCandidates(anyCollection(), any(), any(), any())).thenReturn(rows);
    }

    void claim() {
        when(runs.claimRecoveryCheck(eq("tenant-a"), eq("run-1"), eq(4L), any(), any(), any())).thenReturn(1);
    }

    @Test void unavailableTemporalCannotInventAnOrphanOrTerminalCancellation() {
        worker.tick();
        verifyNoInteractions(runs, state);
    }

    @Test void observedStateIsPassedWithOriginalVersionToATransactionalRecheck() {
        poll(List.of(run)); claim();
        when(temporal.describeWorkflow("attached-workflow")).thenReturn(TemporalExecutor.WorkflowState.CLOSED);
        worker.tick();
        verify(state).recover(eq(run), eq(TemporalExecutor.WorkflowState.CLOSED),
                argThat(cutoff -> cutoff.isBefore(Instant.now().minusSeconds(590))), any());
        verify(runs, never()).save(any());
        assertEquals("CANCELLING", run.getStatus());
    }

    @Test void missingAttachmentUsesDeterministicWorkflowIdentity() {
        poll(List.of(run)); claim();
        run.setTemporalWorkflowId(null);
        worker.tick();
        verify(temporal).describeWorkflow("soar-tenant-a-run-1");
    }

    @Test void anotherReplicasClaimPreventsDuplicateProbe() {
        poll(List.of(run));
        worker.tick();
        verify(temporal, never()).describeWorkflow(anyString());
        verifyNoInteractions(state);
    }

    @Test void oneFailedProbeDoesNotAbortTheOtherRecords() {
        var other = new SoarRunEntity();
        other.setId("run-2"); other.setTenantId("tenant-b"); other.setRowVersion(1L);
        other.setTemporalWorkflowId("other-workflow");
        poll(List.of(run, other)); claim();
        when(temporal.describeWorkflow("attached-workflow")).thenThrow(new IllegalStateException("probe failed"));
        when(runs.claimRecoveryCheck(eq("tenant-b"), eq("run-2"), eq(1L), any(), any(), any())).thenReturn(1);
        when(temporal.describeWorkflow("other-workflow")).thenReturn(TemporalExecutor.WorkflowState.CLOSED);
        worker.tick();
        verify(state).recover(eq(other), eq(TemporalExecutor.WorkflowState.CLOSED), any(), any());
    }

    @Test void boundedScanIncludesAttachedWaitStatesAndUsesDurableRetryTime() {
        poll(List.of(run)); claim();
        worker.tick();
        verify(runs).findRecoveryCandidates(argThat(statuses -> statuses.size() == 5
                && statuses.containsAll(List.of("RUNNING", "DISPATCHING", "CANCELLING", "WAITING_INPUT", "WAITING_APPROVAL"))),
                any(), any(), argThat(page -> page.getPageSize() == 100));
        verify(runs).claimRecoveryCheck(eq("tenant-a"), eq("run-1"), eq(4L), any(), any(),
                argThat(next -> next.isAfter(Instant.now().plusSeconds(50))));
    }
}
