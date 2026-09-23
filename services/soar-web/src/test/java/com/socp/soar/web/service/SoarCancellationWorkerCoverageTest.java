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
class SoarCancellationWorkerCoverageTest {
    @Mock SoarRunRepository runs;
    @Mock TemporalExecutor temporal;
    SoarCancellationWorker worker;
    SoarRunEntity run;

    @BeforeEach void setup() {
        worker = new SoarCancellationWorker(runs, temporal);
        run = new SoarRunEntity();
        run.setId("run-1"); run.setTenantId("tenant-a"); run.setRowVersion(7L);
        run.setStatus("CANCELLING"); run.setTemporalWorkflowId("workflow");
        run.setUpdatedAt(Instant.now().minusSeconds(600));
    }

    void poll() {
        when(temporal.isAvailable()).thenReturn(true);
        when(runs.findCancellationCandidates(any(), any())).thenReturn(List.of(run));
    }

    void claim() {
        when(runs.claimCancellation(eq("tenant-a"), eq("run-1"), eq(7L), eq("workflow"), any(), any())).thenReturn(1);
    }

    @Test void signalAcceptanceDoesNotPretendTheWorkflowMadeProgress() {
        poll(); claim();
        Instant progress = run.getUpdatedAt();
        worker.tick();
        verify(temporal).cancelWorkflow("workflow");
        verify(runs, never()).save(any());
        assertEquals(progress, run.getUpdatedAt());
        assertEquals("CANCELLING", run.getStatus());
    }

    @Test void lostClaimCannotSendASecondCancellation() {
        poll();
        worker.tick();
        verify(temporal, never()).cancelWorkflow(anyString());
    }

    @Test void unavailableTemporalDoesNotConsumeRetryTime() {
        worker.tick();
        verifyNoInteractions(runs);
    }

    @Test void missingAttachmentRemainsForRecovery() {
        poll();
        run.setTemporalWorkflowId(null);
        worker.tick();
        verify(temporal, never()).cancelWorkflow(anyString());
        verify(runs, never()).claimCancellation(anyString(), anyString(), anyLong(), anyString(), any(), any());
    }

    @Test void failedSignalHasAlreadyRotatedItsRetryHintAndDoesNotSaveAStaleEntity() {
        poll(); claim();
        doThrow(new IllegalStateException("offline")).when(temporal).cancelWorkflow("workflow");
        worker.tick();
        verify(runs).claimCancellation(eq("tenant-a"), eq("run-1"), eq(7L), eq("workflow"), any(),
                argThat(next -> next.isAfter(Instant.now().plusSeconds(20))));
        verify(runs, never()).save(any());
    }

    @Test void oneFailedRowDoesNotBlockOtherRows() {
        when(temporal.isAvailable()).thenReturn(true);
        var other = new SoarRunEntity();
        other.setId("other"); other.setTenantId("tenant-b"); other.setRowVersion(2L); other.setTemporalWorkflowId("other-workflow");
        when(runs.findCancellationCandidates(any(), any())).thenReturn(List.of(run, other));
        claim();
        doThrow(new IllegalStateException("offline")).when(temporal).cancelWorkflow("workflow");
        when(runs.claimCancellation(eq("tenant-b"), eq("other"), eq(2L), eq("other-workflow"), any(), any())).thenReturn(1);
        worker.tick();
        verify(temporal).cancelWorkflow("other-workflow");
    }
}
