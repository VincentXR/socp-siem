package com.socp.soar.web.temporal;

import com.socp.soar.web.temporal.request.SoarNodeRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static com.socp.soar.web.temporal.SoarWorkflowSupport.Harness;
import static com.socp.soar.web.temporal.SoarWorkflowSupport.nodeRows;
import static com.socp.soar.web.temporal.SoarWorkflowSupport.rowById;
import static com.socp.soar.web.temporal.SoarWorkflowSupport.output;

/**
 * Temporal test-environment evidence for the linear SOAR interpreter path:
 * START -&gt; ACTION -&gt; END and CONDITION routing on both ports.
 */
class SoarWorkflowLinearTest {

    private Harness harness;

    @BeforeEach
    void boot() {
        harness = Harness.boot();
    }

    @AfterEach
    void close() {
        harness.close();
    }

    @Test
    void linearStartActionEndSucceeds() {
        String definition = new SoarWorkflowSupport.Def()
                .node("start", "START")
                .node("act", "ACTION", "actionRef", "test.echo/run")
                .node("end", "END", "outcome", "SUCCEEDED")
                .edge("start", "act")
                .edge("act", "end")
                .json();
        harness.startWorkflow(definition, "{}");

        SoarWorkflowResult result = harness.result();

        assertThat(result.status()).isEqualTo("SUCCEEDED");
        assertThat(result.errorCode()).isNull();
        assertThat(result.runId()).isEqualTo(harness.runId());

        List<Map<String, Object>> rows = nodeRows(result);
        assertThat(rowById(rows, "start").get("status")).isEqualTo("SUCCEEDED");
        assertThat(rowById(rows, "act").get("status")).isEqualTo("SUCCEEDED");
        assertThat(output(rowById(rows, "act")).get("echo")).isEqualTo("act");
        assertThat(rowById(rows, "end").get("status")).isEqualTo("SUCCEEDED");

        SoarWorkflowSupport.ActivityFake fake = harness.fake();
        assertThat(fake.countOf("markRunStarted")).isEqualTo(1);
        assertThat(fake.countOf("markRunCompleted")).isEqualTo(1);
        assertThat(fake.lastCompletedUpdate().status()).isEqualTo("SUCCEEDED");

        // Exactly one connector action attempt for the ACTION node.
        List<SoarNodeRequest> executeNodeRequests = fake.executeNodeRequests();
        assertThat(executeNodeRequests).hasSize(1);
        assertThat(executeNodeRequests.get(0).nodeId()).isEqualTo("act");
        assertThat(executeNodeRequests.get(0).attemptNo()).isEqualTo(1);
        assertThat(executeNodeRequests.get(0).tenantId()).isEqualTo("tenant-a");
        assertThat(executeNodeRequests.get(0).nodeType()).isEqualTo("ACTION");

        // Non-action nodes are durable-node recorded; ACTION nodes are not.
        List<String> recordedNodeIds = fake.callsNamed("recordNode").stream()
                .map(call -> ((SoarNodeRequest) call.arg(0)).nodeId())
                .toList();
        assertThat(recordedNodeIds).containsExactly("start", "end");
        assertThat(recordedNodeIds).doesNotContain("act");
    }

    @Test
    void conditionRoutesTrueBranch() {
        String definition = conditionDefinition();
        harness.startWorkflow(definition, "{\"data\":{\"severity\":\"high\"}}");

        SoarWorkflowResult result = harness.result();

        assertThat(result.status()).isEqualTo("SUCCEEDED");
        List<Map<String, Object>> rows = nodeRows(result);
        assertThat(output(rowById(rows, "cond")).get("matched")).isEqualTo(true);
        // The true END was reached; the false END never executed.
        assertThat(rowById(rows, "endTrue")).isNotNull();
        assertThat(rowById(rows, "endFalse")).isNull();
    }

    @Test
    void conditionRoutesFalseBranch() {
        String definition = conditionDefinition();
        harness.startWorkflow(definition, "{\"data\":{\"severity\":\"low\"}}");

        SoarWorkflowResult result = harness.result();

        assertThat(result.status()).isEqualTo("SUPPRESSED");
        List<Map<String, Object>> rows = nodeRows(result);
        assertThat(output(rowById(rows, "cond")).get("matched")).isEqualTo(false);
        assertThat(rowById(rows, "endFalse")).isNotNull();
        assertThat(rowById(rows, "endTrue")).isNull();
    }

    private static String conditionDefinition() {
        return new SoarWorkflowSupport.Def()
                .node("start", "START")
                .node("cond", "CONDITION", "expression", "data.severity == \"high\"")
                .node("endTrue", "END", "outcome", "SUCCEEDED")
                .node("endFalse", "END", "outcome", "SUPPRESSED")
                .edge("start", "cond")
                .edge("cond", "endTrue", "true")
                .edge("cond", "endFalse", "false")
                .json();
    }
}
