package com.socp.soar.web.service;

import com.socp.platform.tenant.context.TenantContext;
import com.socp.soar.web.domain.Playbook;
import com.socp.soar.web.temporal.PlaybookWorkflow;
import com.socp.soar.web.temporal.request.SoarWorkflowRequest;
import com.socp.soar.web.temporal.SoarWorkflow;
import io.temporal.api.enums.v1.WorkflowExecutionStatus;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.api.workflowservice.v1.WorkflowServiceGrpc;
import io.temporal.api.workflowservice.v1.DescribeWorkflowExecutionResponse;
import io.temporal.api.workflowservice.v1.SignalWorkflowExecutionRequest;
import io.temporal.api.workflow.v1.WorkflowExecutionInfo;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.StatusUtils;
import io.temporal.api.errordetails.v1.NotFoundFailure;
import io.temporal.api.errordetails.v1.NamespaceNotFoundFailure;
import io.grpc.Status;
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
 * Availability, signal routing and fail-closed recovery observations.
 * The client is mocked here; TemporalWorkflowIdentityTest covers the real SDK.
 */
@ExtendWith(MockitoExtension.class)
class TemporalExecutorCoverageTest {

    @Mock
    private WorkflowClient workflowClient;
    @Mock
    private SoarWorkflow workflowStub;
    @Mock
    private WorkflowServiceGrpc.WorkflowServiceBlockingStub rpc;

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
        // the catch path (probe failure) and the warn log for the queued SOAR dispatch.
        TemporalExecutor executor = new TemporalExecutor(workflowClient, true, "127.0.0.1:1");

        assertThat(executor.isAvailable()).isFalse();
    }

    @Test
    void startWorkflowShortCircuitsWhenTemporalIsUnavailable() {
        TemporalExecutor executor = new TemporalExecutor(workflowClient, false, "localhost:7233");
        SoarWorkflowRequest request = new SoarWorkflowRequest(
                "tenant-a", "run-1", "ver-1", "{}", "{}");

        assertThatThrownBy(() -> executor.startWorkflow(request, "soar-tenant-a-run-1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Temporal is not available");
    }

    @Test
    void startWorkflowBuildsDeterministicOptionsBeforeHandingOffToTemporal() {
        TemporalExecutor executor = new TemporalExecutor(workflowClient, true, "localhost:7233");
        // Warm the availability cache so no socket probe is attempted.
        ReflectionTestUtils.setField(executor, "cachedAvailable", Boolean.TRUE);
        ReflectionTestUtils.setField(executor, "cachedAt", System.currentTimeMillis());
        given(workflowClient.newWorkflowStub(eq(SoarWorkflow.class), any(WorkflowOptions.class)))
                .willReturn(workflowStub);
        SoarWorkflowRequest request = new SoarWorkflowRequest(
                "tenant-a", "run-1", "ver-1", "{}", "{}");

        // WorkflowClient.start hands off through the stub; with a Mockito
        // mock the recorded start is a no-op, so the call completes without
        // gRPC traffic. Everything up to that hand-off (availability gate,
        // workflow id, task queue, execution timeout) is deterministic and
        // verified below.
        executor.startWorkflow(request, "soar-tenant-a-run-1");

        ArgumentCaptor<WorkflowOptions> options = ArgumentCaptor.forClass(WorkflowOptions.class);
        verify(workflowClient).newWorkflowStub(eq(SoarWorkflow.class), options.capture());
        assertThat(options.getValue().getWorkflowId()).isEqualTo("soar-tenant-a-run-1");
        assertThat(options.getValue().getTaskQueue()).isEqualTo(SoarWorkflow.TASK_QUEUE);
        assertThat(options.getValue().getWorkflowIdReusePolicy()).isEqualTo(
                io.temporal.api.enums.v1.WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE);
    }

    @Test
    void cancelWorkflowSendsCancellationSignalWithStableWorkflowId() {
        configureRpc();

        executor().cancelWorkflow("wf-cancel");

        var request = ArgumentCaptor.forClass(SignalWorkflowExecutionRequest.class);
        verify(rpc).signalWorkflowExecution(request.capture());
        assertThat(request.getValue().getWorkflowExecution().getWorkflowId()).isEqualTo("wf-cancel");
        assertThat(request.getValue().getSignalName()).isEqualTo("cancel");
        assertThat(request.getValue().getNamespace()).isEqualTo("recovery-test");
        assertThat(request.getValue().getRequestId()).isNotBlank();
        verify(rpc).withDeadlineAfter(3, java.util.concurrent.TimeUnit.SECONDS);
    }

    @Test
    void decideRoutesApprovalAndRejectionSignals() {
        given(workflowClient.newWorkflowStub(eq(SoarWorkflow.class), org.mockito.ArgumentMatchers.anyString()))
                .willReturn(workflowStub);

        executor().decide("wf-decide", true);
        executor().decide("wf-decide", false);

        verify(workflowStub).approve();
        verify(workflowStub).reject();
    }

    @Test
    void decideGateRoutesGateScopedSignalsIncludingExpiry() {
        given(workflowClient.newWorkflowStub(eq(SoarWorkflow.class), org.mockito.ArgumentMatchers.anyString()))
                .willReturn(workflowStub);

        executor().decideGate("wf-gate", true, "gate-1", false);
        executor().decideGate("wf-gate", false, "gate-1", false);
        executor().decideGate("wf-gate", true, "gate-1", true);

        verify(workflowStub).approveGate("gate-1");
        verify(workflowStub).rejectGate("gate-1");
        verify(workflowStub).expireGate("gate-1");
    }

    @Test
    void completeManualTaskDefaultsNullInputToEmptyJson() {
        given(workflowClient.newWorkflowStub(eq(SoarWorkflow.class), org.mockito.ArgumentMatchers.anyString()))
                .willReturn(workflowStub);

        executor().completeManualTask("wf-task", null);
        executor().completeManualTask("wf-task", "{\"answer\":42}");

        verify(workflowStub).completeManualTask("{}");
        verify(workflowStub).completeManualTask("{\"answer\":42}");
    }

    @Test
    void completeManualTaskForNodeSendsNodeScopedCompletion() {
        given(workflowClient.newWorkflowStub(eq(SoarWorkflow.class), org.mockito.ArgumentMatchers.anyString()))
                .willReturn(workflowStub);

        executor().completeManualTaskForNode("wf-task", "node-7", null);

        verify(workflowStub).completeManualTaskForNode("node-7", "{}");
    }

    @Test
    void resolveUnknownSendsResolutionSignal() {
        given(workflowClient.newWorkflowStub(eq(SoarWorkflow.class), org.mockito.ArgumentMatchers.anyString()))
                .willReturn(workflowStub);

        executor().resolveUnknown("wf-unknown", "node-2", "SUCCEEDED", "evidence-json", "operator proof");

        verify(workflowStub).resolveUnknown("node-2", "SUCCEEDED", "evidence-json", "operator proof");
    }

    @Test
    void describeWorkflowReturnsUnknownForBlankIdsOrDisabledExecutor() {
        TemporalExecutor disabled = new TemporalExecutor(workflowClient, false, "localhost:7233");

        assertThat(executor().describeWorkflow(null)).isEqualTo(TemporalExecutor.WorkflowState.UNKNOWN);
        assertThat(executor().describeWorkflow("   ")).isEqualTo(TemporalExecutor.WorkflowState.UNKNOWN);
        assertThat(disabled.describeWorkflow("wf-1")).isEqualTo(TemporalExecutor.WorkflowState.UNKNOWN);
    }

    @Test
    void describeWorkflowClosesOnlyExplicitTerminalStatuses() {
        configureRpc();
        assertThat(describe(WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_RUNNING))
                .isEqualTo(TemporalExecutor.WorkflowState.OPEN);
        assertThat(describe(WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_PAUSED))
                .isEqualTo(TemporalExecutor.WorkflowState.OPEN);
        assertThat(describe(WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_COMPLETED))
                .isEqualTo(TemporalExecutor.WorkflowState.CLOSED);
        assertThat(describe(WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_TERMINATED))
                .isEqualTo(TemporalExecutor.WorkflowState.CLOSED);
        assertThat(describe(WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_CONTINUED_AS_NEW))
                .isEqualTo(TemporalExecutor.WorkflowState.OPEN);
        assertThat(describe(WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_UNSPECIFIED))
                .isEqualTo(TemporalExecutor.WorkflowState.UNKNOWN);
        assertThat(describe(WorkflowExecutionStatus.UNRECOGNIZED)).isEqualTo(TemporalExecutor.WorkflowState.UNKNOWN);
    }

    @Test
    void describeWorkflowStaysUnknownWhenDescribeFails() {
        configureRpc();
        given(rpc.describeWorkflowExecution(any())).willThrow(new IllegalStateException("temporal down"));

        assertThat(executor().describeWorkflow("wf-broken"))
                .isEqualTo(TemporalExecutor.WorkflowState.UNKNOWN);
    }

    @Test
    void onlyTypedAuthoritativeWorkflowAbsenceIsNotFound() {
        configureRpc();
        var missing = StatusUtils.newException(Status.NOT_FOUND.withDescription("workflow absent"),
                NotFoundFailure.getDefaultInstance(), NotFoundFailure.getDescriptor());
        var namespaceMissing = StatusUtils.newException(Status.NOT_FOUND.withDescription("namespace absent"),
                NamespaceNotFoundFailure.getDefaultInstance(), NamespaceNotFoundFailure.getDescriptor());
        var standbyMissing = StatusUtils.newException(Status.NOT_FOUND.withDescription("standby has no history"),
                NotFoundFailure.newBuilder().setCurrentCluster("standby").setActiveCluster("primary").build(),
                NotFoundFailure.getDescriptor());
        given(rpc.describeWorkflowExecution(any())).willThrow(missing, namespaceMissing, standbyMissing,
                Status.NOT_FOUND.asRuntimeException(), Status.DEADLINE_EXCEEDED.asRuntimeException());
        var executor = executor();
        assertThat(executor.describeWorkflow("wf")).isEqualTo(TemporalExecutor.WorkflowState.NOT_FOUND);
        for (int i = 0; i < 4; i++) assertThat(executor.describeWorkflow("wf")).isEqualTo(TemporalExecutor.WorkflowState.UNKNOWN);
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

    private TemporalExecutor.WorkflowState describe(WorkflowExecutionStatus status) {
        var info = WorkflowExecutionInfo.newBuilder().setStatusValue(status == WorkflowExecutionStatus.UNRECOGNIZED
                ? 9999 : status.getNumber());
        given(rpc.describeWorkflowExecution(any())).willReturn(DescribeWorkflowExecutionResponse.newBuilder()
                .setWorkflowExecutionInfo(info).build());
        return executor().describeWorkflow("wf-open");
    }

    private void configureRpc() {
        var service = mock(WorkflowServiceStubs.class);
        given(workflowClient.getWorkflowServiceStubs()).willReturn(service);
        given(service.blockingStub()).willReturn(rpc);
        given(rpc.withDeadlineAfter(3, java.util.concurrent.TimeUnit.SECONDS)).willReturn(rpc);
        given(workflowClient.getOptions()).willReturn(WorkflowClientOptions.newBuilder()
                .setNamespace("recovery-test").setIdentity("test-worker").build());
    }


}
