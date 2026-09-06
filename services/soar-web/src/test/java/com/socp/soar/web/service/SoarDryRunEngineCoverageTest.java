package com.socp.soar.web.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.socp.platform.tenant.context.TenantContext;
import com.socp.soar.web.definition.SoarDefinitionValidator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Coverage for {@link SoarDryRunEngine}. The walk loop used to append the next
 * hop to the visited window before the cycle check, which stopped the walk
 * right after the entry node; the check now runs first, so the traversal
 * simulates every reachable node until an END, a cycle or the step bound.
 */
class SoarDryRunEngineCoverageTest {

    private SoarDryRunEngine engine;

    @BeforeEach
    void setUp() {
        TenantContext.set("tenant-a");
        engine = new SoarDryRunEngine(new ObjectMapper(), new SoarDefinitionValidator(new ObjectMapper()));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    @SuppressWarnings("unchecked")
    void startEntryNodeIsSimulatedWithoutSideEffects() {
        String definition = """
                {
                  "schemaVersion": "soar.playbook/v2",
                  "entryNodeId": "start",
                  "nodes": [
                    {"id": "start", "type": "START"},
                    {"id": "act", "type": "ACTION", "actionRef": "socp.alert/get"},
                    {"id": "end", "type": "END", "outcome": "SUCCEEDED"}
                  ],
                  "edges": [
                    {"from": "start", "to": "act"},
                    {"from": "act", "to": "end"}
                  ]
                }
                """;

        Map<String, Object> output = engine.run(definition,
                Map.of("alertId", "a-1"), Map.of("host", "web-1"));

        assertThat(output)
                .containsEntry("status", "SIMULATED")
                .containsEntry("mode", "DRY_RUN")
                .containsEntry("sideEffectsSuppressed", true);
        assertThat((String) output.get("definitionHash")).isNotBlank();
        assertThat((Integer) output.get("steps")).isGreaterThan(0);
        assertThat((List<?>) output.get("warnings")).isNotEmpty();

        List<Map<String, Object>> nodes = (List<Map<String, Object>>) output.get("nodes");
        assertThat(nodes).hasSize(3);
        assertThat(nodes).extracting(node -> node.get("nodeId"))
                .containsExactly("start", "act", "end");
        Map<String, Object> start = nodes.get(0);
        assertThat(start)
                .containsEntry("nodeId", "start")
                .containsEntry("nodeType", "START")
                .containsEntry("status", "SIMULATED")
                .containsEntry("sideEffectsSuppressed", true);
        assertThat((Map<String, Object>) start.get("output")).containsEntry("started", true);

        Map<String, Object> variables = (Map<String, Object>) output.get("variables");
        assertThat(variables).containsEntry("alertId", "a-1");
        assertThat((Map<String, Object>) variables.get("subject")).containsEntry("host", "web-1");
        assertThat((Map<String, Object>) variables.get("run"))
                .containsEntry("id", "dry-run")
                .containsEntry("simulation", true);
    }

    @Test
    @SuppressWarnings("unchecked")
    void secretLikeInputsAreRedactedInTheVariableSnapshot() {
        String definition = """
                {
                  "schemaVersion": "soar.playbook/v2",
                  "entryNodeId": "start",
                  "nodes": [
                    {"id": "start", "type": "START"},
                    {"id": "end", "type": "END", "outcome": "SUCCEEDED"}
                  ],
                  "edges": [
                    {"from": "start", "to": "end"}
                  ]
                }
                """;

        Map<String, Object> output = engine.run(definition,
                Map.of("apiToken", "super-secret-value", "plain", "visible"), null);

        Map<String, Object> variables = (Map<String, Object>) output.get("variables");
        assertThat(variables).containsEntry("apiToken", "[REDACTED]");
        assertThat(variables).containsEntry("plain", "visible");
    }

    @Test
    void rejectsDefinitionThatFailsValidation() {
        String missingEntry = """
                {
                  "schemaVersion": "soar.playbook/v2",
                  "nodes": [
                    {"id": "start", "type": "START"},
                    {"id": "end", "type": "END", "outcome": "SUCCEEDED"}
                  ],
                  "edges": [
                    {"from": "start", "to": "end"}
                  ]
                }
                """;
        assertThatThrownBy(() -> engine.run(missingEntry, Map.of(), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SOAR_DEFINITION_INVALID");

        assertThatThrownBy(() -> engine.run("not-json{", Map.of(), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SOAR_DEFINITION_INVALID");
    }
}
