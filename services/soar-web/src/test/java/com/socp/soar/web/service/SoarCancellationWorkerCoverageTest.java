package com.socp.soar.web.service;

import com.socp.platform.tenant.context.TenantContext;
import com.socp.soar.web.persistence.entity.SoarRunEntity;
import com.socp.soar.web.persistence.repository.SoarRunRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class SoarCancellationWorkerCoverageTest {

    @Mock
    private SoarRunRepository runs;
    @Mock
    private TemporalExecutor temporal;

    private SoarCancellationWorker worker;

    @BeforeEach
    void setUp() {
        TenantContext.set("tenant-a");
        worker = new SoarCancellationWorker(runs, temporal);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void cancellingRunIsSignalledAndProjectionTouched() {
        SoarRunEntity run = run("CANCELLING", "soar-tenant-a-run-1");
        Instant before = run.getUpdatedAt();
        given(temporal.isAvailable()).willReturn(true);
        given(runs.findTop100ByStatusOrderByUpdatedAtAsc("CANCELLING")).willReturn(List.of(run));

        worker.tick();

        verify(temporal).cancelWorkflow("soar-tenant-a-run-1");
        verify(runs).save(run);
        assertThat(run.getStatus()).isEqualTo("CANCELLING");
        assertThat(run.getUpdatedAt()).isNotNull();
        assertThat(run.getUpdatedAt().isAfter(before)).isTrue();
    }

    @Test
    void runWithoutWorkflowIdIsSkipped() {
        SoarRunEntity run = run("CANCELLING", null);
        given(temporal.isAvailable()).willReturn(true);
        given(runs.findTop100ByStatusOrderByUpdatedAtAsc("CANCELLING")).willReturn(List.of(run));

        worker.tick();

        verify(temporal, never()).cancelWorkflow(anyString());
        verify(runs, never()).save(any(SoarRunEntity.class));
    }

    @Test
    void signalFailureKeepsRunCancellingForTheNextTick() {
        SoarRunEntity run = run("CANCELLING", "soar-tenant-a-run-1");
        given(temporal.isAvailable()).willReturn(true);
        given(runs.findTop100ByStatusOrderByUpdatedAtAsc("CANCELLING")).willReturn(List.of(run));
        willThrow(new IllegalStateException("temporal unreachable")).given(temporal)
                .cancelWorkflow("soar-tenant-a-run-1");

        worker.tick();

        assertThat(run.getStatus()).isEqualTo("CANCELLING");
        verify(runs, never()).save(any(SoarRunEntity.class));
    }

    @Test
    void unavailableTemporalSkipsThePoll() {
        given(temporal.isAvailable()).willReturn(false);

        worker.tick();

        verify(runs, never()).findTop100ByStatusOrderByUpdatedAtAsc(anyString());
        verify(runs, never()).save(any(SoarRunEntity.class));
    }

    @Test
    void emptyPollTouchesNothing() {
        given(temporal.isAvailable()).willReturn(true);
        given(runs.findTop100ByStatusOrderByUpdatedAtAsc(eq("CANCELLING"))).willReturn(List.of());

        worker.tick();

        verify(temporal, never()).cancelWorkflow(anyString());
        verify(runs, never()).save(any(SoarRunEntity.class));
    }

    private static SoarRunEntity run(String status, String workflowId) {
        SoarRunEntity run = new SoarRunEntity();
        run.setId("run-1");
        run.setTenantId("tenant-a");
        run.setStatus(status);
        run.setTemporalWorkflowId(workflowId);
        run.setUpdatedAt(Instant.now().minusSeconds(30));
        return run;
    }
}
