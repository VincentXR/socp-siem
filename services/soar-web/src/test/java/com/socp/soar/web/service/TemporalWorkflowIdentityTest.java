package com.socp.soar.web.service;

import com.socp.soar.web.temporal.SoarWorkflow;
import com.socp.soar.web.temporal.SoarWorkflowResult;
import com.socp.soar.web.temporal.request.SoarWorkflowRequest;
import io.temporal.client.WorkflowExecutionAlreadyStarted;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.workflow.Workflow;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TemporalWorkflowIdentityTest {
    @Test @Timeout(30)
    void maintenanceObservesActualOpenClosedAndUntypedMissingWorkflowStates() throws Exception {
        try (var environment = TestWorkflowEnvironment.newInstance()) {
            environment.newWorker(SoarWorkflow.TASK_QUEUE).registerWorkflowImplementationTypes(HeldWorkflow.class);
            environment.start();
            var client = environment.getWorkflowClient();
            var executor = spy(new TemporalExecutor(client, true, "in-process-test-server"));
            doReturn(true).when(executor).isAvailable();
            String id = "maintenance-state";
            executor.startWorkflow(new SoarWorkflowRequest("tenant-a", id, "version", "{}", "{}"), id);
            assertEquals(TemporalExecutor.WorkflowState.OPEN, executor.describeWorkflow(id));
            executor.cancelWorkflow(id);
            client.newUntypedWorkflowStub(id).getResult(5, TimeUnit.SECONDS, SoarWorkflowResult.class);
            assertEquals(TemporalExecutor.WorkflowState.CLOSED, executor.describeWorkflow(id));
            // SDK 1.38's in-process service emits a bare NOT_FOUND here. Prove
            // that transport fact rather than treating it as typed workflow absence.
            var missing = assertThrows(io.grpc.StatusRuntimeException.class, () -> client.getWorkflowServiceStubs()
                    .blockingStub().describeWorkflowExecution(
                            io.temporal.api.workflowservice.v1.DescribeWorkflowExecutionRequest.newBuilder()
                                    .setNamespace(client.getOptions().getNamespace())
                                    .setExecution(io.temporal.api.common.v1.WorkflowExecution.newBuilder()
                                            .setWorkflowId("never-started")).build()));
            assertEquals(io.grpc.Status.Code.NOT_FOUND, missing.getStatus().getCode());
            assertNull(io.temporal.serviceclient.StatusUtils.getFailure(missing,
                    io.temporal.api.errordetails.v1.NotFoundFailure.class));
            assertEquals(TemporalExecutor.WorkflowState.UNKNOWN, executor.describeWorkflow("never-started"));
        }
    }

    @Test @Timeout(30)
    void oneRunCannotStartAgainWhileOpenOrAfterItHasClosed() throws Exception {
        try (var environment = TestWorkflowEnvironment.newInstance()) {
            environment.newWorker(SoarWorkflow.TASK_QUEUE).registerWorkflowImplementationTypes(HeldWorkflow.class);
            environment.start();
            var client = environment.getWorkflowClient();
            var executor = spy(new TemporalExecutor(client, true, "in-process-test-server"));
            // Only the TCP availability probe is substituted; start and signals
            // run against Temporal's actual in-process test service.
            doReturn(true).when(executor).isAvailable();
            var request = new SoarWorkflowRequest("tenant-a", "run-1", "version", "{}", "{}");
            String id = "soar-tenant-a-run-1";
            var original = executor.startWorkflow(request, id);
            assertThrows(WorkflowExecutionAlreadyStarted.class, () -> executor.startWorkflow(request, id));
            executor.decide(id, true);
            var result = client.newUntypedWorkflowStub(id).getResult(5, TimeUnit.SECONDS, SoarWorkflowResult.class);
            assertEquals("SUCCEEDED", result.status());
            var duplicate = assertThrows(WorkflowExecutionAlreadyStarted.class, () -> executor.startWorkflow(request, id));
            assertEquals(original.getRunId(), duplicate.getExecution().getRunId());

            String nextId = "soar-tenant-a-run-2";
            var next = executor.startWorkflow(new SoarWorkflowRequest("tenant-a", "run-2", "version", "{}", "{}"), nextId);
            assertNotEquals(original.getRunId(), next.getRunId());
            executor.decide(nextId, true);
            assertEquals("run-2", client.newUntypedWorkflowStub(nextId).getResult(5, TimeUnit.SECONDS, SoarWorkflowResult.class).runId());
        }
    }

    @Test @Timeout(30)
    void everySignalHelperConnectsToAnExistingWorkflowAndPreservesItsArguments() throws Exception {
        try (var environment = TestWorkflowEnvironment.newInstance()) {
            environment.newWorker(SoarWorkflow.TASK_QUEUE).registerWorkflowImplementationTypes(HeldWorkflow.class);
            environment.start();
            var client = environment.getWorkflowClient();
            var executor = spy(new TemporalExecutor(client, true, "in-process-test-server"));
            doReturn(true).when(executor).isAvailable();
            var expected = java.util.List.of("cancel", "approve", "reject", "approve:gate", "reject:gate", "expire:gate",
                    "manual:{\"ticket\":1}", "manual:node:{\"ticket\":2}", "unknown:node:CONFIRMED_NOT_EXECUTED:receipt:reviewed");
            for (int index = 0; index < expected.size(); index++) {
                String id = "signal-check-" + index;
                executor.startWorkflow(new SoarWorkflowRequest("tenant-a", id, "version", "{}", "{}"), id);
                switch (index) {
                    case 0 -> executor.cancelWorkflow(id);
                    case 1 -> executor.decide(id, true);
                    case 2 -> executor.decide(id, false);
                    case 3 -> executor.decideGate(id, true, "gate", false);
                    case 4 -> executor.decideGate(id, false, "gate", false);
                    case 5 -> executor.decideGate(id, false, "gate", true);
                    case 6 -> executor.completeManualTask(id, "{\"ticket\":1}");
                    case 7 -> executor.completeManualTaskForNode(id, "node", "{\"ticket\":2}");
                    case 8 -> executor.resolveUnknown(id, "node", "CONFIRMED_NOT_EXECUTED", "receipt", "reviewed");
                    default -> throw new AssertionError(index);
                }
                var result = client.newUntypedWorkflowStub(id).getResult(5, TimeUnit.SECONDS, SoarWorkflowResult.class);
                assertEquals(expected.get(index), result.variablesJson(), "signal " + index);
            }
        }
    }

    public static class HeldWorkflow implements SoarWorkflow {
        private boolean released;
        private String received;
        @Override public SoarWorkflowResult execute(SoarWorkflowRequest request) {
            Workflow.await(() -> released);
            return new SoarWorkflowResult(request.runId(), request.versionId(), "SUCCEEDED", "[]", null, null, received);
        }
        private void accept(String value) { received = value; released = true; }
        @Override public void approve() { accept("approve"); }
        @Override public void reject() { accept("reject"); }
        @Override public void cancel() { accept("cancel"); }
        @Override public void approveGate(String key) { accept("approve:" + key); }
        @Override public void rejectGate(String key) { accept("reject:" + key); }
        @Override public void expireGate(String key) { accept("expire:" + key); }
        @Override public void completeManualTask(String json) { accept("manual:" + json); }
        @Override public void completeManualTaskForNode(String node, String json) { accept("manual:" + node + ":" + json); }
        @Override public void resolveUnknown(String node, String resolution, String evidence, String reason) {
            accept("unknown:" + node + ":" + resolution + ":" + evidence + ":" + reason);
        }
    }
}
