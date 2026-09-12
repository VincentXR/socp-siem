package com.socp.soar.web.service;

import com.socp.platform.tenant.context.TenantContext;
import com.socp.soar.web.persistence.entity.SoarRunEntity;
import com.socp.soar.web.persistence.repository.SoarRunRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class SoarRunRecoveryWorkerCoverageTest {

    private static final long STALE_SECONDS = 600L;

    @Mock
    private SoarRunRepository runs;
    @Mock
    private TemporalExecutor temporal;

    private SoarRunRecoveryWorker worker;

    @BeforeEach
    void setUp() {
        TenantContext.set("tenant-a");
        worker = new SoarRunRecoveryWorker(runs, temporal, STALE_SECONDS);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void closedWorkflowTerminalizesProjectionAsActionUnknown() {
        SoarRunEntity run = staleRun("RUNNING", "soar-tenant-a-run-1");
        given(runs.findTop100ByStatusInAndUpdatedAtBeforeOrderByUpdatedAtAsc(anyCollection(), any()))
                .willReturn(List.of(run));
        given(temporal.describeWorkflow("soar-tenant-a-run-1")).willReturn(TemporalExecutor.WorkflowState.CLOSED);

        worker.tick();

        assertThat(run.getStatus()).isEqualTo("ACTION_UNKNOWN");
        assertThat(run.getErrorCode()).isEqualTo("SOAR_PROJECTION_STALE");
        assertThat(run.getErrorMessage()).contains("Temporal workflow closed");
        assertThat(run.getCompletedAt()).isNotNull();
        verify(runs).save(run);
    }

    @Test
    void openWorkflowKeepsProjectionUntouched() {
        SoarRunEntity run = staleRun("RUNNING", "soar-tenant-a-run-2");
        given(runs.findTop100ByStatusInAndUpdatedAtBeforeOrderByUpdatedAtAsc(anyCollection(), any()))
                .willReturn(List.of(run));
        given(temporal.describeWorkflow("soar-tenant-a-run-2")).willReturn(TemporalExecutor.WorkflowState.OPEN);

        worker.tick();

        assertThat(run.getStatus()).isEqualTo("RUNNING");
        assertThat(run.getCompletedAt()).isNull();
        verify(runs, never()).save(any(SoarRunEntity.class));
    }

    @Test
    void unknownDescribeKeepsProjectionUntouched() {
        SoarRunEntity run = staleRun("DISPATCHING", "soar-tenant-a-run-3");
        given(runs.findTop100ByStatusInAndUpdatedAtBeforeOrderByUpdatedAtAsc(anyCollection(), any()))
                .willReturn(List.of(run));
        given(temporal.describeWorkflow("soar-tenant-a-run-3")).willReturn(TemporalExecutor.WorkflowState.UNKNOWN);

        worker.tick();

        assertThat(run.getStatus()).isEqualTo("DISPATCHING");
        verify(runs, never()).save(any(SoarRunEntity.class));
    }

    @Test
    void orphanWithoutWorkflowIdIsMarkedTimedOut() {
        SoarRunEntity run = staleRun("DISPATCHING", null);
        given(runs.findTop100ByStatusInAndUpdatedAtBeforeOrderByUpdatedAtAsc(anyCollection(), any()))
                .willReturn(List.of(run));

        worker.tick();

        assertThat(run.getStatus()).isEqualTo("TIMED_OUT");
        assertThat(run.getErrorCode()).isEqualTo("SOAR_PROJECTION_STALE");
        assertThat(run.getErrorMessage()).contains("recovery lease");
        verify(runs).save(run);
        verify(temporal, never()).describeWorkflow(anyString());
    }

    @Test
    void staleCancellationWithoutWorkflowIsCompletedAsCancelled() {
        SoarRunEntity run = staleRun("CANCELLING", "   ");
        given(runs.findTop100ByStatusInAndUpdatedAtBeforeOrderByUpdatedAtAsc(anyCollection(), any()))
                .willReturn(List.of(run));

        worker.tick();

        assertThat(run.getStatus()).isEqualTo("CANCELLED");
        assertThat(run.getErrorCode()).isEqualTo("SOAR_RUN_CANCELLED");
        verify(temporal, never()).describeWorkflow(anyString());
    }

    @Test
    void staleCancellationAfterClosedWorkflowIsCompletedAsCancelled() {
        SoarRunEntity run = staleRun("CANCELLING", "soar-tenant-a-run-cancelled");
        given(runs.findTop100ByStatusInAndUpdatedAtBeforeOrderByUpdatedAtAsc(anyCollection(), any()))
                .willReturn(List.of(run));

        worker.tick();

        assertThat(run.getStatus()).isEqualTo("CANCELLED");
        assertThat(run.getErrorCode()).isEqualTo("SOAR_RUN_CANCELLED");
        verify(runs).save(run);
    }

    @Test
    void rowTerminalizedAfterTheSnapshotIsSkipped() {
        SoarRunEntity completed = staleRun("SUCCEEDED", "soar-tenant-a-run-4");
        SoarRunEntity refreshed = staleRun("RUNNING", "soar-tenant-a-run-5");
        refreshed.setUpdatedAt(Instant.now());
        given(runs.findTop100ByStatusInAndUpdatedAtBeforeOrderByUpdatedAtAsc(anyCollection(), any()))
                .willReturn(List.of(completed, refreshed));

        worker.tick();

        assertThat(completed.getStatus()).isEqualTo("SUCCEEDED");
        assertThat(refreshed.getStatus()).isEqualTo("RUNNING");
        verify(runs, never()).save(any(SoarRunEntity.class));
        verify(temporal, never()).describeWorkflow(anyString());
    }

    @Test
    void runWithoutUpdatedAtIsSkipped() {
        SoarRunEntity run = staleRun("RUNNING", "soar-tenant-a-run-6");
        run.setUpdatedAt(null);
        given(runs.findTop100ByStatusInAndUpdatedAtBeforeOrderByUpdatedAtAsc(anyCollection(), any()))
                .willReturn(List.of(run));

        worker.tick();

        assertThat(run.getStatus()).isEqualTo("RUNNING");
        verify(runs, never()).save(any(SoarRunEntity.class));
    }

    @Test
    void workerWithoutTemporalClientMarksEveryStaleRunTimedOut() {
        SoarRunRecoveryWorker noTemporal = new SoarRunRecoveryWorker(runs);
        SoarRunEntity run = staleRun("RUNNING", "soar-tenant-a-run-7");
        run.setUpdatedAt(Instant.now().minusSeconds(7 * 24 * 3600L));
        given(runs.findTop100ByStatusInAndUpdatedAtBeforeOrderByUpdatedAtAsc(anyCollection(), any()))
                .willReturn(List.of(run));

        noTemporal.tick();

        assertThat(run.getStatus()).isEqualTo("TIMED_OUT");
        verify(runs).save(run);
    }

    @Test
    void emptyScanTouchesNothing() {
        given(runs.findTop100ByStatusInAndUpdatedAtBeforeOrderByUpdatedAtAsc(anyCollection(), any()))
                .willReturn(List.of());

        worker.tick();

        verify(runs, never()).save(any(SoarRunEntity.class));
        verify(temporal, never()).describeWorkflow(anyString());
    }

    @Test
    void recoveryScansOnlyActiveStatusesWithStaleCutoff() {
        ArgumentCaptor<Collection> statuses = ArgumentCaptor.forClass(Collection.class);
        ArgumentCaptor<Instant> cutoff = ArgumentCaptor.forClass(Instant.class);
        given(runs.findTop100ByStatusInAndUpdatedAtBeforeOrderByUpdatedAtAsc(anyCollection(), any()))
                .willReturn(List.of());

        Instant before = Instant.now().minusSeconds(STALE_SECONDS + 5);
        worker.tick();
        Instant after = Instant.now().minusSeconds(STALE_SECONDS - 5);

        verify(runs).findTop100ByStatusInAndUpdatedAtBeforeOrderByUpdatedAtAsc(statuses.capture(), cutoff.capture());
        Collection capturedStatuses = statuses.getValue();
        assertThat(capturedStatuses.containsAll(List.of("DISPATCHING", "RUNNING", "CANCELLING"))).isTrue();
        assertThat(capturedStatuses.size()).isEqualTo(3);
        assertThat(cutoff.getValue().isAfter(before)).isTrue();
        assertThat(cutoff.getValue().isBefore(after)).isTrue();
    }

    private static SoarRunEntity staleRun(String status, String workflowId) {
        SoarRunEntity run = new SoarRunEntity();
        run.setId("run-1");
        run.setTenantId("tenant-a");
        run.setStatus(status);
        run.setTemporalWorkflowId(workflowId);
        run.setUpdatedAt(Instant.now().minusSeconds(STALE_SECONDS * 2));
        return run;
    }
}
