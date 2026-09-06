package com.socp.soar.web.temporal.v2;

import com.socp.soar.web.temporal.request.SoarV2NodeRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static com.socp.soar.web.temporal.v2.SoarV2WorkflowSupport.Harness;
import static com.socp.soar.web.temporal.v2.SoarV2WorkflowSupport.nodeRows;
import static com.socp.soar.web.temporal.v2.SoarV2WorkflowSupport.output;
import static com.socp.soar.web.temporal.v2.SoarV2WorkflowSupport.rowById;

/**
 * Temporal test-environment evidence for the SWITCH interpreter path.
 *
 * <p>The runtime vocabulary verified here (matching {@code switchBranch} in
 * {@code SoarV2WorkflowImpl} and the {@code SWITCH} rules in
 * {@code SoarDefinitionValidator}): the value to route comes from the top-level
 * node {@code expression}, the case map is a {@code config.cases} array whose
 * items carry {@code value} plus the outgoing edge {@code port} (the validator
 * accepts the {@code when}/{@code toPort} spellings too), and a value that
 * matches no case walks the default branch (blank or {@code default} edge).
 */
class SoarV2WorkflowSwitchTest {

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
    void switchRoutesMatchedCasePortToItsTargetThenSucceeds() {
        // Severity "high" matches the first case -> only the case action runs.
        String definition = new SoarV2WorkflowSupport.Def()
                .node("start", "START")
                .node("sw", "SWITCH",
                        "expression", "data.severity",
                        "config", Map.of("cases", List.of(
                                Map.of("value", "high", "port", "high"),
                                Map.of("value", "medium", "port", "medium"))))
                .node("actHigh", "ACTION", "actionRef", "test.echo/high")
                .node("actMedium", "ACTION", "actionRef", "test.echo/medium")
                .node("endHigh", "END", "outcome", "SUCCEEDED")
                .node("endMedium", "END", "outcome", "SUCCEEDED")
                .node("endDefault", "END", "outcome", "SUCCEEDED")
                .edge("start", "sw")
                .edge("sw", "actHigh", "high")
                .edge("actHigh", "endHigh")
                .edge("sw", "actMedium", "medium")
                .edge("actMedium", "endMedium")
                .edge("sw", "endDefault")
                .json();
        harness.startWorkflow(definition, "{\"data\":{\"severity\":\"high\"}}");

        SoarV2WorkflowResult result = harness.result();

        assertThat(result.status()).isEqualTo("SUCCEEDED");
        assertThat(result.errorCode()).isNull();
        List<Map<String, Object>> rows = nodeRows(result);
        assertThat(rowById(rows, "sw").get("status")).isEqualTo("SUCCEEDED");
        assertThat(output(rowById(rows, "sw")).get("value")).isEqualTo("high");
        assertThat(output(rowById(rows, "sw")).get("port")).isEqualTo("high");
        // Only the matched case target executed; sibling case and default never ran.
        assertThat(rowById(rows, "endHigh")).isNotNull();
        assertThat(rowById(rows, "endMedium")).isNull();
        assertThat(rowById(rows, "endDefault")).isNull();

        SoarV2WorkflowSupport.ActivityFake fake = harness.fake();
        List<SoarV2NodeRequest> requests = fake.executeNodeRequests();
        assertThat(requests).hasSize(1);
        assertThat(requests.get(0).nodeId()).isEqualTo("actHigh");
        assertThat(fake.lastCompletedUpdate().status()).isEqualTo("SUCCEEDED");
    }

    @Test
    void switchRoutesDefaultBranchWhenNoCaseMatches() {
        // Severity "low" is not a declared case -> the default edge is walked.
        String definition = new SoarV2WorkflowSupport.Def()
                .node("start", "START")
                .node("sw", "SWITCH",
                        "expression", "data.severity",
                        "config", Map.of("cases", List.of(
                                Map.of("value", "high", "port", "high"))))
                .node("actDefault", "ACTION", "actionRef", "test.echo/default")
                .node("end", "END", "outcome", "SUCCEEDED")
                .edge("start", "sw")
                .edge("sw", "actDefault")
                .edge("actDefault", "end")
                .json();
        harness.startWorkflow(definition, "{\"data\":{\"severity\":\"low\"}}");

        SoarV2WorkflowResult result = harness.result();

        assertThat(result.status()).isEqualTo("SUCCEEDED");
        assertThat(result.errorCode()).isNull();
        List<Map<String, Object>> rows = nodeRows(result);
        assertThat(rowById(rows, "sw").get("status")).isEqualTo("SUCCEEDED");
        assertThat(output(rowById(rows, "sw")).get("value")).isEqualTo("low");
        assertThat(output(rowById(rows, "sw")).get("port")).isEqualTo("default");
        assertThat(rowById(rows, "end")).isNotNull();

        SoarV2WorkflowSupport.ActivityFake fake = harness.fake();
        List<SoarV2NodeRequest> requests = fake.executeNodeRequests();
        assertThat(requests).hasSize(1);
        assertThat(requests.get(0).nodeId()).isEqualTo("actDefault");
        assertThat(fake.lastCompletedUpdate().status()).isEqualTo("SUCCEEDED");
    }
}
