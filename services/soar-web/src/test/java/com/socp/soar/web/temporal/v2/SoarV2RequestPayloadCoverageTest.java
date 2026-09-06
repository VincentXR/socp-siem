package com.socp.soar.web.temporal.v2;

import com.socp.soar.web.temporal.request.SoarV2NodeRequest;
import com.socp.soar.web.temporal.request.SoarV2WorkflowRequest;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Construction, compat-constructor defaults and child/branch factories for V2 payloads. */
class SoarV2RequestPayloadCoverageTest {

    @Test
    void workflowRequestCompatConstructorsFillHistoricalDefaults() {
        SoarV2WorkflowRequest eightArg = new SoarV2WorkflowRequest(
                "tenant-a", "run-1", "ver-1", "{}", "{}", "series-1", "node-1", true);
        assertThat(eightArg.initialIterationPath()).isEmpty();
        assertThat(eightArg.stopAtNodeId()).isNull();
        assertThat(eightArg.executionSeriesId()).isEqualTo("series-1");

        SoarV2WorkflowRequest fiveArg = new SoarV2WorkflowRequest(
                "tenant-a", "run-1", "ver-1", "{}", "{}");
        assertThat(fiveArg.executionSeriesId()).isEqualTo("run-1");
        assertThat(fiveArg.resumeFromNodeId()).isNull();
        assertThat(fiveArg.topLevelProjection()).isTrue();
        assertThat(fiveArg.initialIterationPath()).isEmpty();
        assertThat(fiveArg.stopAtNodeId()).isNull();

        SoarV2WorkflowRequest sevenArg = new SoarV2WorkflowRequest(
                "tenant-a", "run-1", "ver-1", "{}", "{}", "series-1", "node-2");
        assertThat(sevenArg.executionSeriesId()).isEqualTo("series-1");
        assertThat(sevenArg.resumeFromNodeId()).isEqualTo("node-2");
        assertThat(sevenArg.topLevelProjection()).isTrue();
    }

    @Test
    void childOfOverloadsInheritParentIdentityAndNeverCompleteParentProjection() {
        SoarV2WorkflowRequest parent = new SoarV2WorkflowRequest(
                "tenant-a", "run-1", "ver-1", "{}", "{}", "series-1", null, true);

        SoarV2WorkflowRequest minimal = SoarV2WorkflowRequest.childOf(parent, "{\"step\":1}");
        assertThat(minimal.tenantId()).isEqualTo("tenant-a");
        assertThat(minimal.runId()).isEqualTo("run-1");
        assertThat(minimal.versionId()).isEqualTo("ver-1");
        assertThat(minimal.executionSeriesId()).isEqualTo("series-1");
        assertThat(minimal.definitionJson()).isEqualTo("{\"step\":1}");
        assertThat(minimal.inputJson()).isEqualTo("{}");
        assertThat(minimal.topLevelProjection()).isFalse();
        assertThat(minimal.initialIterationPath()).isEmpty();
        assertThat(minimal.stopAtNodeId()).isNull();

        SoarV2WorkflowRequest withInput = SoarV2WorkflowRequest.childOf(
                parent, "{}", "{\"k\":\"v\"}");
        assertThat(withInput.inputJson()).isEqualTo("{\"k\":\"v\"}");

        SoarV2WorkflowRequest withNullInput = SoarV2WorkflowRequest.childOf(
                parent, "{}", null);
        assertThat(withNullInput.inputJson()).isEqualTo("{}");

        SoarV2WorkflowRequest withPath = SoarV2WorkflowRequest.childOf(
                parent, "{}", "{\"k\":\"v\"}", "0.1");
        assertThat(withPath.initialIterationPath()).isEqualTo("0.1");

        SoarV2WorkflowRequest withNullPath = SoarV2WorkflowRequest.childOf(
                parent, "{}", null, null);
        assertThat(withNullPath.initialIterationPath()).isEmpty();
        assertThat(withNullPath.inputJson()).isEqualTo("{}");
    }

    @Test
    void branchOfStopsBeforeConvergeNodeAndCarriesParentSeries() {
        SoarV2WorkflowRequest parent = new SoarV2WorkflowRequest(
                "tenant-a", "run-1", "ver-1", "{}", "{}", "series-1", null, true);

        SoarV2WorkflowRequest branch = SoarV2WorkflowRequest.branchOf(
                parent, "{\"step\":2}", null, "node-7", "0.2", "converge");

        assertThat(branch.tenantId()).isEqualTo("tenant-a");
        assertThat(branch.runId()).isEqualTo("run-1");
        assertThat(branch.executionSeriesId()).isEqualTo("series-1");
        assertThat(branch.definitionJson()).isEqualTo("{\"step\":2}");
        assertThat(branch.inputJson()).isEqualTo("{}");
        assertThat(branch.resumeFromNodeId()).isEqualTo("node-7");
        assertThat(branch.initialIterationPath()).isEqualTo("0.2");
        assertThat(branch.stopAtNodeId()).isEqualTo("converge");
        assertThat(branch.topLevelProjection()).isFalse();
    }

    @Test
    void nodeRequestCompactConstructorGuardsTargetMap() {
        SoarV2NodeRequest nullTarget = new SoarV2NodeRequest(
                "tenant-a", "run-1", "node-1", "ACTION", "socp.alert/get",
                "0", "{}", "idem-1", "", null, 0);
        assertThat(nullTarget.target()).isEmpty();

        SoarV2NodeRequest emptyTarget = new SoarV2NodeRequest(
                "tenant-a", "run-1", "node-1", "ACTION", "socp.alert/get",
                "0", "{}", "idem-1", "", Map.of(), 0);
        assertThat(emptyTarget.target()).isEmpty();

        SoarV2NodeRequest withTarget = new SoarV2NodeRequest(
                "tenant-a", "run-1", "node-1", "ACTION", "socp.alert/get",
                "0", "{}", "idem-1", "", new java.util.LinkedHashMap<>(Map.of("id", "alert-1")), 2);
        assertThat(withTarget.target()).containsEntry("id", "alert-1");
        assertThatThrownBy(() -> withTarget.target().put("injected", "value"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void nodeRequestCompatConstructorsDefaultConnectorContext() {
        SoarV2NodeRequest legacy = new SoarV2NodeRequest(
                "tenant-a", "run-1", "node-1", "ACTION", "socp.alert/get",
                "0", "{}", "idem-1");
        assertThat(legacy.connectionRef()).isEmpty();
        assertThat(legacy.target()).isEmpty();
        assertThat(legacy.attemptNo()).isZero();

        SoarV2NodeRequest withConnection = new SoarV2NodeRequest(
                "tenant-a", "run-1", "node-1", "ACTION", "firewall/block-ioc",
                "0.1", "{}", "idem-2", "conn-1", Map.of("ip", "10.0.0.5"));
        assertThat(withConnection.connectionRef()).isEqualTo("conn-1");
        assertThat(withConnection.target()).containsEntry("ip", "10.0.0.5");
        assertThat(withConnection.attemptNo()).isZero();
    }

    @Test
    void workflowResultCompatConstructorDefaultsVariablesJson() {
        SoarV2WorkflowResult legacy = new SoarV2WorkflowResult(
                "run-1", "ver-1", "SUCCEEDED", "[]", null, null);
        assertThat(legacy.runId()).isEqualTo("run-1");
        assertThat(legacy.versionId()).isEqualTo("ver-1");
        assertThat(legacy.status()).isEqualTo("SUCCEEDED");
        assertThat(legacy.nodesJson()).isEqualTo("[]");
        assertThat(legacy.errorCode()).isNull();
        assertThat(legacy.errorMessage()).isNull();
        assertThat(legacy.variablesJson()).isEqualTo("{}");

        SoarV2WorkflowResult full = new SoarV2WorkflowResult(
                "run-2", "ver-2", "FAILED", "[]", "E_CODE", "boom", "{\"v\":1}");
        assertThat(full.errorCode()).isEqualTo("E_CODE");
        assertThat(full.errorMessage()).isEqualTo("boom");
        assertThat(full.variablesJson()).isEqualTo("{\"v\":1}");
    }
}
