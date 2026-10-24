package com.socp.soar.web.temporal;

import com.socp.soar.web.temporal.request.SoarWorkflowRequest;
import com.socp.soar.web.temporal.request.SoarNodeRequest;
import io.temporal.activity.ActivityOptions;
import io.temporal.client.WorkflowOptions;
import io.temporal.common.RetryOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.testing.WorkflowReplayer;
import io.temporal.workflow.Workflow;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.assertj.core.api.Assertions.assertThat;

/** An early cancellation must not become a node-budget failure or execute another action. */
class SoarWorkflowAdmissionTest {
    @ParameterizedTest
    @CsvSource({"CANCELLED,CANCELLED,RUN_CANCELLED", "LIMIT_EXCEEDED,FAILED,EXECUTION_LIMIT_EXCEEDED",
            "MISSING,FAILED,SOAR_NODE_ADMISSION_FAILED", "TERMINAL,FAILED,SOAR_NODE_ADMISSION_FAILED"})
    void admissionDenialKeepsItsReasonAndDoesNotExecuteNodes(String admission, String status, String code) {
        try (var harness = SoarWorkflowSupport.Harness.boot()) {
            harness.fake().admissionOutcome(admission);
            String definition = new SoarWorkflowSupport.Def()
                    .node("start", "START").node("act", "ACTION", "actionRef", "test/action")
                    .node("end", "END").edge("start", "act").edge("act", "end").json();
            harness.startWorkflow(definition, "{}", SoarWorkflowSupport.VERSION_ID, 10);

            var result = harness.result();

            assertThat(result.status()).isEqualTo(status);
            assertThat(result.errorCode()).isEqualTo(code);
            assertThat(harness.fake().executeNodeRequests()).isEmpty();
            assertThat(harness.fake().countOf("reserveNodeExecutionOutcome")).isEqualTo(1);
            assertThat(harness.fake().countOf("reserveNodeExecution")).isZero();
            assertThat(harness.fake().lastCompletedUpdate().status()).isEqualTo(status);
            assertThat(harness.fake().lastCompletedUpdate().errorCode()).isEqualTo(code);
        }
    }
    @ParameterizedTest @ValueSource(booleans = {true, false})
    void replaysThePreVersionBooleanAdmissionCommand(boolean admitted) throws Exception {
        try (var env = TestWorkflowEnvironment.newInstance()) {
            var fake = new SoarWorkflowSupport.ActivityFake().admissionOutcome(admitted ? "RESERVED" : "CANCELLED");
            var worker = env.newWorker(SoarWorkflow.TASK_QUEUE);
            worker.registerWorkflowImplementationTypes(PreOutcomeAdmissionWorkflow.class);
            worker.registerActivitiesImplementations(fake);
            env.start();
            String definition = new SoarWorkflowSupport.Def().entry("end").node("end", "END").json();
            var workflow = env.getWorkflowClient().newWorkflowStub(SoarWorkflow.class,
                    WorkflowOptions.newBuilder().setWorkflowId("old-admission-history")
                            .setTaskQueue(SoarWorkflow.TASK_QUEUE).build());
            var request = new SoarWorkflowRequest("tenant-a", "run", "version", definition, "{}",
                    "run", null, false, "", null, 10, 0);

            var result = workflow.execute(request);
            assertThat(result.status()).isEqualTo(admitted ? "SUCCEEDED" : "FAILED");
            assertThat(result.errorCode()).isEqualTo(admitted ? null : "EXECUTION_LIMIT_EXCEEDED");
            assertThat(fake.countOf("reserveNodeExecution")).isEqualTo(1);
            WorkflowReplayer.replayWorkflowExecution(
                    env.getWorkflowClient().fetchHistory("old-admission-history"), SoarWorkflowImpl.class);
        }
    }

    /** Minimal synthetic pre-upgrade histories: first reservation denied, or admitted into END. */
    public static class PreOutcomeAdmissionWorkflow extends SoarWorkflowImpl {
        @Override public SoarWorkflowResult execute(SoarWorkflowRequest request) {
            var activity = Workflow.newActivityStub(SoarActivity.class, ActivityOptions.newBuilder()
                    .setStartToCloseTimeout(Duration.ofSeconds(60))
                    .setScheduleToCloseTimeout(Duration.ofMinutes(10))
                    .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(1).build()).build());
            if (activity.reserveNodeExecution(request.tenantId(), request.runId(), request.executionBudgetLimit())) {
                activity.recordNode(new SoarNodeRequest(request.tenantId(), request.runId(), "end", "END", "", "",
                                "{}", SoarWorkflowGraphSupport.idempotency(request.tenantId(), request.runId(),
                                request.executionSeriesId(), "end", ""), "", Map.of()),
                        new SoarNodeResult("SUCCEEDED", "{\"outcome\":\"SUCCEEDED\"}", null, null));
                return new SoarWorkflowResult(request.runId(), request.versionId(), "SUCCEEDED", "[]", null, null, "{}");
            }
            return new SoarWorkflowResult(request.runId(), request.versionId(), "FAILED", "[]",
                    "EXECUTION_LIMIT_EXCEEDED", "run exceeded its shared node execution budget", "{}");
        }
    }

}
