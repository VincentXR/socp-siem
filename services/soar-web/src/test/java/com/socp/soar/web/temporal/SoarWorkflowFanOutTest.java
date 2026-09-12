package com.socp.soar.web.temporal;

import com.socp.soar.web.temporal.request.SoarNodeRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static com.socp.soar.web.temporal.SoarWorkflowSupport.Harness;
import static com.socp.soar.web.temporal.SoarWorkflowSupport.map;
import static com.socp.soar.web.temporal.SoarWorkflowSupport.nodeRows;
import static com.socp.soar.web.temporal.SoarWorkflowSupport.output;
import static com.socp.soar.web.temporal.SoarWorkflowSupport.rowById;
import static com.socp.soar.web.temporal.SoarWorkflowSupport.variables;

/**
 * Temporal test-environment evidence for the FOREACH and PARALLEL fan-out
 * semantics implemented through real child workflow executions.
 */
class SoarWorkflowFanOutTest {

    private Harness harness;

    @BeforeEach
    void boot() {
        harness = Harness.boot();
    }

    @AfterEach
    void close() {
        harness.close();
    }

    // ------------------------------------------------------------- (c) FOREACH

    @Test
    void foreachRunsEveryItemAndLastBranchWinsVariables() {
        String definition = new SoarWorkflowSupport.Def()
                .node("start", "START")
                .node("each", "FOREACH",
                        "config", SoarWorkflowSupport.obj(
                                "itemsPath", "items", "itemVariable", "item"),
                        "limits", SoarWorkflowSupport.obj("concurrency", 2))
                .node("act", "ACTION", "actionRef", "test.echo/run")
                .node("end", "END", "outcome", "SUCCEEDED")
                .edge("start", "each")
                .edge("each", "act", "body")
                .edge("each", "end", "done")
                .edge("act", "end")
                .json();
        harness.startWorkflow(definition,
                "{\"items\":[\"item-0\",\"item-1\",\"item-2\"]}");

        SoarWorkflowResult result = harness.result();

        assertThat(result.status()).isEqualTo("SUCCEEDED");
        SoarWorkflowSupport.ActivityFake fake = harness.fake();
        assertThat(fake.countOf("markRunStarted")).isEqualTo(1);
        assertThat(fake.countOf("markRunCompleted")).isEqualTo(1);
        assertThat(fake.lastCompletedUpdate().status()).isEqualTo("SUCCEEDED");

        // Exactly three child executions, one per item, distinguished by path.
        List<SoarNodeRequest> requests = fake.executeNodeRequests();
        assertThat(requests).hasSize(3);
        List<String> paths = requests.stream()
                .map(SoarNodeRequest::iterationPath)
                .sorted(Comparator.naturalOrder())
                .toList();
        assertThat(paths).containsExactly("0", "1", "2");

        // Per-item variable injection is visible in each child request input.
        for (SoarNodeRequest request : requests) {
            assertThat(request.nodeId()).isEqualTo("act");
            int index = Integer.parseInt(request.iterationPath());
            assertThat(map(request.inputJson()).get("item")).isEqualTo("item-" + index);
            assertThat(map(request.inputJson()).get("tenantId")).isEqualTo("tenant-a");
        }

        // Parent projection has one body ACTION row per iteration plus the
        // FOREACH summary showing three iterations.
        List<Map<String, Object>> rows = nodeRows(result);
        List<Map<String, Object>> bodyRows = rows.stream()
                .filter(row -> "act".equals(row.get("nodeId")))
                .toList();
        assertThat(bodyRows).hasSize(3);
        assertThat(bodyRows).extracting(row -> row.get("iterationPath"))
                .containsExactlyInAnyOrder("0", "1", "2");
        for (Map<String, Object> bodyRow : bodyRows) {
            assertThat(bodyRow.get("status")).isEqualTo("SUCCEEDED");
        }
        Map<String, Object> eachOutput = output(rowById(rows, "each"));
        assertThat(eachOutput.get("iterations")).isEqualTo(3);
        assertThat(eachOutput.get("allSucceeded")).isEqualTo(true);

        // Later branches win on an explicit variable conflict: the last item
        // is the surviving value in the terminal variable snapshot.
        assertThat(variables(result).get("item")).isEqualTo("item-2");
    }

    // ------------------------------------------------------------- (d) PARALLEL

    @Test
    void parallelAllSuccessFailsRunWhenABranchFails() {
        String definition = parallelDefinition("ALL_SUCCESS");
        harness.fake().overrideNode("b1", new SoarNodeResult(
                "FAILED", "{\"echo\":\"b1\"}", "SIMULATED_FAILURE", "branch one boom"));
        harness.startWorkflow(definition, "{}");

        SoarWorkflowResult result = harness.result();

        assertThat(result.status()).isEqualTo("FAILED");
        assertThat(result.errorCode()).isEqualTo("PARALLEL_BRANCH_FAILED");
        assertThat(result.errorMessage()).contains("branches failed");

        List<Map<String, Object>> rows = nodeRows(result);
        assertThat(rowById(rows, "b1").get("status")).isEqualTo("FAILED");
        assertThat(rowById(rows, "b2").get("status")).isEqualTo("SUCCEEDED");
        Map<String, Object> joinRow = rowById(rows, "join");
        assertThat(joinRow.get("status")).isEqualTo("FAILED");
        assertThat(joinRow.get("errorCode")).isEqualTo("PARALLEL_BRANCH_FAILED");
        assertThat(rowById(rows, "end")).isNull();

        SoarWorkflowSupport.ActivityFake fake = harness.fake();
        assertThat(fake.lastCompletedUpdate().status()).isEqualTo("FAILED");
        assertThat(fake.lastCompletedUpdate().errorCode()).isEqualTo("PARALLEL_BRANCH_FAILED");
    }

    @Test
    void parallelAnySuccessEndsPartiallySucceededWhenABranchFails() {
        String definition = parallelDefinition("ANY_SUCCESS");
        harness.fake().overrideNode("b1", new SoarNodeResult(
                "FAILED", "{\"echo\":\"b1\"}", "SIMULATED_FAILURE", "branch one boom"));
        harness.startWorkflow(definition, "{}");

        SoarWorkflowResult result = harness.result();

        assertThat(result.status()).isEqualTo("PARTIALLY_SUCCEEDED");

        List<Map<String, Object>> rows = nodeRows(result);
        assertThat(rowById(rows, "b1").get("status")).isEqualTo("FAILED");
        assertThat(rowById(rows, "b2").get("status")).isEqualTo("SUCCEEDED");
        assertThat(rowById(rows, "join").get("status")).isEqualTo("SUCCEEDED");
        assertThat(rowById(rows, "end").get("status")).isEqualTo("PARTIALLY_SUCCEEDED");

        SoarWorkflowSupport.ActivityFake fake = harness.fake();
        assertThat(fake.lastCompletedUpdate().status()).isEqualTo("PARTIALLY_SUCCEEDED");
    }

    private static String parallelDefinition(String strategy) {
        return new SoarWorkflowSupport.Def()
                .node("start", "START")
                .node("p", "PARALLEL")
                .node("b1", "ACTION", "actionRef", "branch.one/run")
                .node("b2", "ACTION", "actionRef", "branch.two/run")
                .node("join", "JOIN", "strategy", strategy)
                .node("end", "END", "outcome", "SUCCEEDED")
                .edge("start", "p")
                .edge("p", "b1")
                .edge("p", "b2")
                .edge("b1", "join")
                .edge("b2", "join")
                .edge("join", "end")
                .json();
    }
}
