package com.socp.soar.web.temporal;

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
import static com.socp.soar.web.temporal.SoarWorkflowSupport.variables;

/**
 * Temporal test-environment evidence for the SET_VARIABLE and DELAY
 * interpreter paths.
 */
class SoarWorkflowPrimitiveTest {

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
    void setVariableWritesVarsNamespaceAndFeedsTheFinalState() {
        String definition = new SoarWorkflowSupport.Def()
                .node("start", "START")
                .node("setv", "SET_VARIABLE", "config",
                        Map.of("name", "vars.note", "value", "auto"))
                .node("end", "END", "outcome", "SUCCEEDED")
                .edge("start", "setv")
                .edge("setv", "end")
                .json();
        harness.startWorkflow(definition, "{}");

        SoarWorkflowResult result = harness.result();

        assertThat(result.status()).isEqualTo("SUCCEEDED");
        List<Map<String, Object>> rows = nodeRows(result);
        assertThat(rowById(rows, "setv").get("status")).isEqualTo("SUCCEEDED");
        assertThat(output(rowById(rows, "setv")).get("name")).isEqualTo("note");
        assertThat(output(rowById(rows, "setv")).get("value")).isEqualTo("auto");
        // the vars.* write lands in the terminal variable snapshot
        assertThat(variables(result).get("note")).isEqualTo("auto");
    }

    @Test
    void boundedDelayAdvancesThroughVirtualTimeAndSucceeds() {
        String definition = new SoarWorkflowSupport.Def()
                .node("start", "START")
                .node("delay", "DELAY", "config", Map.of("durationSeconds", 2L))
                .node("end", "END", "outcome", "SUCCEEDED")
                .edge("start", "delay")
                .edge("delay", "end")
                .json();
        harness.startWorkflow(definition, "{}");

        SoarWorkflowResult result = harness.result();

        assertThat(result.status()).isEqualTo("SUCCEEDED");
        List<Map<String, Object>> rows = nodeRows(result);
        assertThat(rowById(rows, "delay").get("status")).isEqualTo("SUCCEEDED");
        assertThat(output(rowById(rows, "delay")).get("durationSeconds")).isEqualTo(2);
        SoarWorkflowSupport.ActivityFake fake = harness.fake();
        assertThat(fake.countOf("markRunStarted")).isEqualTo(1);
        assertThat(fake.lastCompletedUpdate().status()).isEqualTo("SUCCEEDED");
    }

    @Test
    void malformedDurableInputFailsExplicitlyInsteadOfBecomingAnEmptyObject() {
        String definition = new SoarWorkflowSupport.Def()
                .node("start", "START")
                .node("end", "END", "outcome", "SUCCEEDED")
                .edge("start", "end")
                .json();

        harness.startWorkflow(definition, "not-json{");

        SoarWorkflowResult result = harness.result();

        assertThat(result.status()).isEqualTo("FAILED");
        assertThat(result.errorCode()).isEqualTo("WORKFLOW_DEFINITION_ERROR");
        assertThat(result.errorMessage()).contains("invalid workflow JSON");
    }
}
