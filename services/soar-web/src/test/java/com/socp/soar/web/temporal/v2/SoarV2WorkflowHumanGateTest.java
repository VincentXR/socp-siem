package com.socp.soar.web.temporal.v2;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static com.socp.soar.web.temporal.v2.SoarV2WorkflowSupport.Def;
import static com.socp.soar.web.temporal.v2.SoarV2WorkflowSupport.Harness;
import static com.socp.soar.web.temporal.v2.SoarV2WorkflowSupport.map;
import static com.socp.soar.web.temporal.v2.SoarV2WorkflowSupport.nodeRows;
import static com.socp.soar.web.temporal.v2.SoarV2WorkflowSupport.obj;
import static com.socp.soar.web.temporal.v2.SoarV2WorkflowSupport.output;
import static com.socp.soar.web.temporal.v2.SoarV2WorkflowSupport.rowById;
import static com.socp.soar.web.temporal.v2.SoarV2WorkflowSupport.variables;

/**
 * Temporal test-environment evidence for human gates: APPROVAL signals and
 * expiry, MANUAL_TASK completion/timeout, and UNKNOWN result resolution.
 */
class SoarV2WorkflowHumanGateTest {

    private static final long GATE_TIMEOUT_SECONDS = 5;
    private static final long GATE_SLEEP_SECONDS = GATE_TIMEOUT_SECONDS + 120;

    private Harness harness;

    @BeforeEach
    void boot() {
        harness = Harness.boot();
    }

    @AfterEach
    void close() {
        harness.close();
    }

    private SoarV2WorkflowSupport.ActivityFake fake() {
        return harness.fake();
    }

    // ------------------------------------------------------------- (e) APPROVAL

    @Test
    void approveGateWithExactKeySucceeds() {
        harness.startWorkflow(approvalDefinition(null, false, null), "{}");
        harness.waitForMethod("markRunWaitingWithPolicyV2");

        harness.approveGate("approval");

        SoarV2WorkflowResult result = harness.result();
        assertThat(result.status()).isEqualTo("SUCCEEDED");
        assertThat(fake().countOf("markRunStarted")).isEqualTo(2);
        assertThat(fake().countOf("markRunCompleted")).isEqualTo(1);
        assertThat(fake().lastCompletedUpdate().status()).isEqualTo("SUCCEEDED");

        List<Map<String, Object>> rows = nodeRows(result);
        Map<String, Object> approvalRow = rowById(rows, "approval");
        assertThat(approvalRow.get("status")).isEqualTo("SUCCEEDED");
        assertThat(output(approvalRow).get("decision")).isEqualTo("approved");
        assertThat(rowById(rows, "act").get("status")).isEqualTo("SUCCEEDED");
        assertThat(rowById(rows, "end").get("status")).isEqualTo("SUCCEEDED");
    }

    @Test
    void rejectGateWithRejectedEdgeFailsTheRun() {
        harness.startWorkflow(approvalDefinition(null, true, "FAILED"), "{}");
        harness.waitForMethod("markRunWaitingWithPolicyV2");

        harness.rejectGate("approval");

        SoarV2WorkflowResult result = harness.result();
        assertThat(result.status()).isEqualTo("FAILED");
        assertThat(result.errorCode()).isEqualTo("APPROVAL_REJECTED");
        assertThat(fake().countOf("markRunStarted")).isEqualTo(2);
        assertThat(fake().lastCompletedUpdate().status()).isEqualTo("FAILED");
        assertThat(fake().lastCompletedUpdate().errorCode()).isEqualTo("APPROVAL_REJECTED");

        List<Map<String, Object>> rows = nodeRows(result);
        Map<String, Object> approvalRow = rowById(rows, "approval");
        assertThat(approvalRow.get("status")).isEqualTo("SUCCEEDED");
        assertThat(approvalRow.get("errorCode")).isEqualTo("APPROVAL_REJECTED");
        assertThat(output(approvalRow).get("decision")).isEqualTo("rejected");
        assertThat(rowById(rows, "act")).isNull();
        assertThat(rowById(rows, "rejected").get("status")).isEqualTo("FAILED");
    }

    @Test
    void rejectGateWithoutRejectedEdgeSuppressesTheRun() {
        harness.startWorkflow(approvalDefinition(null, false, null), "{}");
        harness.waitForMethod("markRunWaitingWithPolicyV2");

        harness.rejectGate("approval");

        SoarV2WorkflowResult result = harness.result();
        assertThat(result.status()).isEqualTo("SUPPRESSED");
        assertThat(result.errorCode()).isEqualTo("APPROVAL_REJECTED");
        // Suppressed path never re-marks the run as started.
        assertThat(fake().countOf("markRunStarted")).isEqualTo(1);
        assertThat(fake().lastCompletedUpdate().status()).isEqualTo("SUPPRESSED");
        assertThat(fake().lastCompletedUpdate().errorCode()).isEqualTo("APPROVAL_REJECTED");

        List<Map<String, Object>> rows = nodeRows(result);
        assertThat(output(rowById(rows, "approval")).get("decision")).isEqualTo("rejected");
    }

    @Test
    void expireGateSuppressesTheRun() {
        harness.startWorkflow(approvalDefinition(null, false, null), "{}");
        harness.waitForMethod("markRunWaitingWithPolicyV2");

        harness.expireGate("approval");

        SoarV2WorkflowResult result = harness.result();
        assertThat(result.status()).isEqualTo("SUPPRESSED");
        assertThat(result.errorCode()).isEqualTo("APPROVAL_EXPIRED");
        assertThat(fake().countOf("markApprovalExpired")).isZero();
        assertThat(fake().lastCompletedUpdate().status()).isEqualTo("SUPPRESSED");
        assertThat(fake().lastCompletedUpdate().errorCode()).isEqualTo("APPROVAL_EXPIRED");

        List<Map<String, Object>> rows = nodeRows(result);
        assertThat(output(rowById(rows, "approval")).get("decision")).isEqualTo("expired");
    }

    @Test
    void approvalTimerExpiryRecordsExpiredMarker() {
        harness.startWorkflow(approvalDefinition((int) GATE_TIMEOUT_SECONDS, false, null), "{}");
        harness.waitForMethod("markRunWaitingWithPolicyV2");

        // Advance virtual time past the gate timeout with no decision signal.
        harness.env().sleep(Duration.ofSeconds(GATE_SLEEP_SECONDS));

        assertThat(fake().countOf("markApprovalExpired")).isEqualTo(1);
        SoarV2WorkflowResult result = harness.result();
        assertThat(result.status()).isEqualTo("SUPPRESSED");
        assertThat(result.errorCode()).isEqualTo("APPROVAL_EXPIRED");
        assertThat(fake().lastCompletedUpdate().status()).isEqualTo("SUPPRESSED");
        assertThat(fake().lastCompletedUpdate().errorCode()).isEqualTo("APPROVAL_EXPIRED");

        List<Map<String, Object>> rows = nodeRows(result);
        assertThat(output(rowById(rows, "approval")).get("decision")).isEqualTo("expired");
    }

    // ---------------------------------------------------------- (f) MANUAL_TASK

    @Test
    void completeManualTaskForNodeResumesAndPreservesInput() {
        harness.startWorkflow(manualDefinition(null, false), "{}");
        harness.waitForMethod("markManualTaskWaiting");

        String userInput = "{\"note\":\"analyst confirmed\",\"ok\":true}";
        harness.completeManualTask("task", userInput);

        SoarV2WorkflowResult result = harness.result();
        assertThat(result.status()).isEqualTo("SUCCEEDED");
        assertThat(fake().countOf("markManualTaskWaiting")).isEqualTo(1);
        assertThat(fake().lastCompletedUpdate().status()).isEqualTo("SUCCEEDED");

        List<Map<String, Object>> rows = nodeRows(result);
        Map<String, Object> taskRow = rowById(rows, "task");
        assertThat(taskRow.get("status")).isEqualTo("SUCCEEDED");
        assertThat(output(taskRow).get("input")).isEqualTo(map(userInput));
        assertThat(rowById(rows, "endOk").get("status")).isEqualTo("SUCCEEDED");
    }

    @Test
    void manualTaskTimeoutEdgeFailsTheRun() {
        harness.startWorkflow(manualDefinition((int) GATE_TIMEOUT_SECONDS, true), "{}");
        harness.waitForMethod("markManualTaskWaiting");

        harness.env().sleep(Duration.ofSeconds(GATE_SLEEP_SECONDS));

        assertThat(fake().countOf("markManualTaskExpired")).isEqualTo(1);
        SoarV2WorkflowResult result = harness.result();
        assertThat(result.status()).isEqualTo("FAILED");
        assertThat(fake().lastCompletedUpdate().status()).isEqualTo("FAILED");

        List<Map<String, Object>> rows = nodeRows(result);
        Map<String, Object> taskRow = rowById(rows, "task");
        assertThat(taskRow.get("status")).isEqualTo("TIMED_OUT");
        assertThat(taskRow.get("errorCode")).isEqualTo("MANUAL_TASK_EXPIRED");
        assertThat(output(taskRow).get("expired")).isEqualTo(true);
        assertThat(rowById(rows, "endFail").get("status")).isEqualTo("FAILED");
    }

    // -------------------------------------------------------------- (g) UNKNOWN

    @Test
    void unknownResolveConfirmedSucceededContinuesTheRun() {
        harness.fake().overrideNode("act", new SoarV2NodeResult(
                "UNKNOWN", "{}", "SOAR_ACTION_RESULT_UNKNOWN", "remote response indeterminate"));
        harness.startWorkflow(unknownDefinition(), "{}");
        harness.waitForMethod("markRunUnknown");

        harness.resolveUnknown("act", "CONFIRMED_SUCCEEDED", "evidence-1", "operator confirmed success");

        SoarV2WorkflowResult result = harness.result();
        assertThat(result.status()).isEqualTo("SUCCEEDED");
        assertThat(result.errorCode()).isNull();
        assertThat(fake().countOf("markRunUnknown")).isEqualTo(1);
        // Initial projection plus the resolution re-mark after the unknown.
        assertThat(fake().countOf("markRunStarted")).isEqualTo(2);
        assertThat(fake().countOf("markRunCompleted")).isEqualTo(1);
        assertThat(fake().lastCompletedUpdate().status()).isEqualTo("SUCCEEDED");

        // The resolution is visible in the terminal variable snapshot.
        Map<String, Object> terminalVariables = variables(result);
        @SuppressWarnings("unchecked")
        Map<String, Object> actOutput = (Map<String, Object>) terminalVariables.get("act");
        assertThat(actOutput).isNotNull();
        assertThat(actOutput.get("resolution")).isEqualTo("CONFIRMED_SUCCEEDED");
        assertThat(actOutput.get("evidence")).isEqualTo("evidence-1");

        // The durable node projection still shows the observed UNKNOWN result.
        List<Map<String, Object>> rows = nodeRows(result);
        assertThat(rowById(rows, "act").get("status")).isEqualTo("UNKNOWN");
        assertThat(rowById(rows, "end").get("status")).isEqualTo("SUCCEEDED");
    }

    // ------------------------------------------------------------------- defs

    private static String approvalDefinition(Integer timeoutSeconds, boolean withRejectedEdge,
                                             String rejectedOutcome) {
        Def definition = new Def()
                .node("start", "START")
                .node("approval", "APPROVAL",
                        "config", obj("timeoutSeconds", timeoutSeconds == null ? 3600 : timeoutSeconds))
                .node("act", "ACTION", "actionRef", "test.echo/run")
                .node("end", "END", "outcome", "SUCCEEDED")
                .edge("start", "approval")
                .edge("approval", "act", "approved")
                .edge("act", "end");
        if (withRejectedEdge) {
            definition.node("rejected", "END", "outcome", rejectedOutcome)
                    .edge("approval", "rejected", "rejected");
        }
        return definition.json();
    }

    private static String manualDefinition(Integer timeoutSeconds, boolean withTimeoutEdge) {
        Def definition = new Def()
                .node("start", "START")
                .node("task", "MANUAL_TASK",
                        "config", obj("timeoutSeconds", timeoutSeconds == null ? 86400 : timeoutSeconds))
                .node("endOk", "END", "outcome", "SUCCEEDED")
                .edge("start", "task")
                .edge("task", "endOk");
        if (withTimeoutEdge) {
            definition.node("endFail", "END", "outcome", "FAILED")
                    .edge("task", "endFail", "timeout");
        }
        return definition.json();
    }

    private static String unknownDefinition() {
        return new Def()
                .node("start", "START")
                .node("act", "ACTION", "actionRef", "test.echo/run")
                .node("end", "END", "outcome", "SUCCEEDED")
                .edge("start", "act")
                .edge("act", "end")
                .json();
    }
}
