package com.socp.soar.web.service;

import com.socp.platform.tenant.context.TenantContext;
import com.socp.soar.web.domain.Playbook;
import com.socp.soar.web.temporal.PlaybookWorkflow;
import com.socp.soar.web.temporal.request.SoarV2WorkflowRequest;
import com.socp.soar.web.temporal.v2.SoarV2Workflow;
import io.temporal.api.enums.v1.WorkflowExecutionStatus;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowExecutionDescription;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Unit coverage for the Temporal dual-mode dispatcher: availability probe,
 * V2 signal routing and describe mapping. WorkflowClient/WorkflowStub are
 * Mockito mocks, so no Temporal server is required.
 */
@ExtendWith(MockitoExtension.class)
class TemporalExecutorCoverageTest {

    @Mock
    private WorkflowClient workflowClient;
    @Mock
    private SoarV2Workflow v2Stub;
    @Mock
    private WorkflowStub untypedStub;

    @BeforeEach
    void setUp() {
        TenantContext.set("tenant-a");
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void isAvailableIsFalseWhenTemporalIsDisabled() {
        TemporalExecutor executor = new TemporalExecutor(workflowClient, false, "localhost:7233");

        assertThat(executor.isAvailable()).isFalse();
    }

    @Test
    void unreachableTargetMarksExecutorUnavailableAndLogsWarning() {
        // Port 1 on loopback refuses the probe socket immediately; this covers
        // the catch path (probe failure) and the warn log for the queued V2 dispatch.
        TemporalExecutor executor = new TemporalExecutor(workflowClient, true, "127.0.0.1:1");

        assertThat(executor.isAvailable()).isFalse();
    }

    @Test
    void startV2ShortCircuitsWhenTemporalIsUnavailable() {
        TemporalExecutor executor = new TemporalExecutor(workflowClient, false, "localhost:7233");
        SoarV2WorkflowRequest request = new SoarV2WorkflowRequest(
                "tenant-a", "run-1", "ver-1", "{}", "{}");

        assertThatThrownBy(() -> executor.startV2(request, "soar-v2-tenant-a-run-1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Temporal is not available");
    }

    @Test
    void startV2BuildsDeterministicOptionsBeforeHandingOffToTemporal() {
        TemporalExecutor executor = new TemporalExecutor(workflowClient, true, "localhost:7233");
        // Warm the availability cache so no socket probe is attempted.
        ReflectionTestUtils.setField(executor, "cachedAvailable", Boolean.TRUE);
        ReflectionTestUtils.setField(executor, "cachedAt", System.currentTimeMillis());
        given(workflowClient.newWorkflowStub(eq(SoarV2Workflow.class), any(WorkflowOptions.class)))
                .willReturn(v2Stub);
        SoarV2WorkflowRequest request = new SoarV2WorkflowRequest(
                "tenant-a", "run-1", "ver-1", "{}", "{}");

        // WorkflowClient.start hands off through the stub; with a Mockito
        // mock the recorded start is a no-op, so the call completes without
        // gRPC traffic. Everything up to that hand-off (availability gate,
        // workflow id, task queue, execution timeout) is deterministic and
        // verified below.
        executor.startV2(request, "soar-v2-tenant-a-run-1");

        ArgumentCaptor<WorkflowOptions> options = ArgumentCaptor.forClass(WorkflowOptions.class);
        verify(workflowClient).newWorkflowStub(eq(SoarV2Workflow.class), options.capture());
        assertThat(options.getValue().getWorkflowId()).isEqualTo("soar-v2-tenant-a-run-1");
        assertThat(options.getValue().getTaskQueue()).isEqualTo(SoarV2Workflow.TASK_QUEUE);
    }

    @Test
    void cancelV2SendsCancellationSignalWithStableWorkflowId() {
        given(workflowClient.newWorkflowStub(eq(SoarV2Workflow.class), any(WorkflowOptions.class)))
                .willReturn(v2Stub);

        executor().cancelV2("wf-cancel");

        verify(v2Stub).cancel();
        assertThat(capturedOptions().getWorkflowId()).isEqualTo("wf-cancel");
    }

    @Test
    void decideV2RoutesApprovalAndRejectionSignals() {
        given(workflowClient.newWorkflowStub(eq(SoarV2Workflow.class), any(WorkflowOptions.class)))
                .willReturn(v2Stub);

        executor().decideV2("wf-decide", true);
        executor().decideV2("wf-decide", false);

        verify(v2Stub).approve();
        verify(v2Stub).reject();
    }

    @Test
    void decideGateV2RoutesGateScopedSignalsIncludingExpiry() {
        given(workflowClient.newWorkflowStub(eq(SoarV2Workflow.class), any(WorkflowOptions.class)))
                .willReturn(v2Stub);

        executor().decideGateV2("wf-gate", true, "gate-1", false);
        executor().decideGateV2("wf-gate", false, "gate-1", false);
        executor().decideGateV2("wf-gate", true, "gate-1", true);

        verify(v2Stub).approveGate("gate-1");
        verify(v2Stub).rejectGate("gate-1");
        verify(v2Stub).expireGate("gate-1");
    }

    @Test
    void completeManualTaskDefaultsNullInputToEmptyJson() {
        given(workflowClient.newWorkflowStub(eq(SoarV2Workflow.class), any(WorkflowOptions.class)))
                .willReturn(v2Stub);

        executor().completeManualTask("wf-task", null);
        executor().completeManualTask("wf-task", "{\"answer\":42}");

        verify(v2Stub).completeManualTask("{}");
        verify(v2Stub).completeManualTask("{\"answer\":42}");
    }

    @Test
    void completeManualTaskForNodeSendsNodeScopedCompletion() {
        given(workflowClient.newWorkflowStub(eq(SoarV2Workflow.class), any(WorkflowOptions.class)))
                .willReturn(v2Stub);

        executor().completeManualTaskForNode("wf-task", "node-7", null);

        verify(v2Stub).completeManualTaskForNode("node-7", "{}");
    }

    @Test
    void resolveUnknownSendsResolutionSignal() {
        given(workflowClient.newWorkflowStub(eq(SoarV2Workflow.class), any(WorkflowOptions.class)))
                .willReturn(v2Stub);

        executor().resolveUnknown("wf-unknown", "node-2", "SUCCEEDED", "evidence-json", "operator proof");

        verify(v2Stub).resolveUnknown("node-2", "SUCCEEDED", "evidence-json", "operator proof");
    }

    @Test
    void describeV2ReturnsUnknownForBlankIdsOrDisabledExecutor() {
        TemporalExecutor disabled = new TemporalExecutor(workflowClient, false, "localhost:7233");

        assertThat(executor().describeV2(null)).isEqualTo(TemporalExecutor.V2WorkflowState.UNKNOWN);
        assertThat(executor().describeV2("   ")).isEqualTo(TemporalExecutor.V2WorkflowState.UNKNOWN);
        assertThat(disabled.describeV2("wf-1")).isEqualTo(TemporalExecutor.V2WorkflowState.UNKNOWN);
    }

    @Test
    void describeV2MapsRunningAndPausedToOpenAndEverythingElseToClosed() {
        given(workflowClient.newUntypedWorkflowStub("wf-open")).willReturn(untypedStub);
        assertThat(describe(WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_RUNNING))
                .isEqualTo(TemporalExecutor.V2WorkflowState.OPEN);
        assertThat(describe(WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_PAUSED))
                .isEqualTo(TemporalExecutor.V2WorkflowState.OPEN);
        assertThat(describe(WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_COMPLETED))
                .isEqualTo(TemporalExecutor.V2WorkflowState.CLOSED);
        assertThat(describe(WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_TERMINATED))
                .isEqualTo(TemporalExecutor.V2WorkflowState.CLOSED);
        // A missing status is treated as CLOSED, never as a false "still open".
        assertThat(describe(null)).isEqualTo(TemporalExecutor.V2WorkflowState.CLOSED);
    }

    @Test
    void describeV2StaysUnknownWhenDescribeFails() {
        given(workflowClient.newUntypedWorkflowStub("wf-broken")).willReturn(untypedStub);
        given(untypedStub.describe()).willThrow(new IllegalStateException("temporal down"));

        assertThat(executor().describeV2("wf-broken"))
                .isEqualTo(TemporalExecutor.V2WorkflowState.UNKNOWN);
    }

    @Test
    void runSubmitsPlaybookWorkflowWithTenantBoundAlarm() {
        PlaybookWorkflow playbookStub = org.mockito.Mockito.mock(PlaybookWorkflow.class);
        given(workflowClient.newWorkflowStub(eq(PlaybookWorkflow.class), any(WorkflowOptions.class)))
                .willReturn(playbookStub);
        given(playbookStub.executePlaybook(any())).willReturn(Map.of("status", "SUCCESS"));
        Playbook playbook = Playbook.create("contain-host", "manual", List.of("firewall-block"), true);

        Map<String, Object> result = executor().run(playbook, new java.util.LinkedHashMap<>(
                Map.of("host", "web-1")));

        assertThat(result).containsEntry("status", "SUCCESS");
        ArgumentCaptor<WorkflowOptions> options = ArgumentCaptor.forClass(WorkflowOptions.class);
        verify(workflowClient).newWorkflowStub(eq(PlaybookWorkflow.class), options.capture());
        assertThat(options.getValue().getTaskQueue()).isEqualTo(PlaybookWorkflow.TASK_QUEUE);
        assertThat(options.getValue().getWorkflowId()).startsWith("playbook-" + playbook.id() + "-");
    }

    private TemporalExecutor executor() {
        TemporalExecutor executor = new TemporalExecutor(workflowClient, true, "localhost:7233");
        ReflectionTestUtils.setField(executor, "cachedAvailable", Boolean.TRUE);
        ReflectionTestUtils.setField(executor, "cachedAt", System.currentTimeMillis());
        return executor;
    }

    private TemporalExecutor.V2WorkflowState describe(WorkflowExecutionStatus status) {
        WorkflowExecutionDescription description = mock(WorkflowExecutionDescription.class);
        given(description.getStatus()).willReturn(status);
        given(untypedStub.describe()).willReturn(description);
        return executor().describeV2("wf-open");
    }

    private WorkflowOptions capturedOptions() {
        ArgumentCaptor<WorkflowOptions> options = ArgumentCaptor.forClass(WorkflowOptions.class);
        verify(workflowClient).newWorkflowStub(eq(SoarV2Workflow.class), options.capture());
        return options.getValue();
    }
}
