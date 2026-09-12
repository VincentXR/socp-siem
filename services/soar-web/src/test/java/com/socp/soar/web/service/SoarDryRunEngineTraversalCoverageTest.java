package com.socp.soar.web.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.socp.platform.tenant.context.TenantContext;
import com.socp.soar.web.definition.SoarDefinitionValidator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Traversal coverage for {@link SoarDryRunEngine}. The walk loop must simulate
 * every reachable node (ACTION/CONDITION/SWITCH/PARALLEL/JOIN/FOREACH/
 * SET_VARIABLE/END) with side effects suppressed, instead of stopping right
 * after the entry node, and must stay bounded on cyclic graphs. Uses the real
 * ObjectMapper and the real SoarDefinitionValidator; no connector, clock,
 * persistence or network is involved.
 */
@ExtendWith(MockitoExtension.class)
class SoarDryRunEngineTraversalCoverageTest {

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
    void walksStartActionEnd() {
        Map<String, Object> output = engine.run("""
                {
                  "schemaVersion": "soar.playbook",
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
                """, Map.of("alertId", "a-1"), Map.of("user", "analyst"));

        assertSimulated(output);
        assertThat(nodeIds(output)).containsExactly("start", "act", "end");
        Map<String, Object> action = nodeResult(output, "act");
        assertThat((Map<String, Object>) action.get("output"))
                .containsEntry("status", "SIMULATED")
                .containsEntry("actionRef", "socp.alert/get")
                .containsEntry("sideEffectSuppressed", true);
        Map<String, Object> end = nodeResult(output, "end");
        assertThat((Map<String, Object>) end.get("output")).containsEntry("outcome", "SUCCEEDED");
    }

    @Test
    @SuppressWarnings("unchecked")
    void conditionRoutesTrueAndFalseBranches() {
        String definition = """
                {
                  "schemaVersion": "soar.playbook",
                  "entryNodeId": "start",
                  "nodes": [
                    {"id": "start", "type": "START"},
                    {"id": "check", "type": "CONDITION", "expression": "severity == \\"high\\""},
                    {"id": "highEnd", "type": "END", "outcome": "SUCCEEDED"},
                    {"id": "act", "type": "ACTION", "actionRef": "socp.alert/get"},
                    {"id": "lowEnd", "type": "END", "outcome": "FAILED"}
                  ],
                  "edges": [
                    {"from": "start", "to": "check"},
                    {"from": "check", "to": "highEnd", "port": "true"},
                    {"from": "check", "to": "act", "port": "false"},
                    {"from": "act", "to": "lowEnd"}
                  ]
                }
                """;

        Map<String, Object> matched = engine.run(definition, Map.of("severity", "high"), Map.of());
        assertSimulated(matched);
        assertThat(nodeIds(matched)).containsExactly("start", "check", "highEnd");
        assertThat(nodeResult(matched, "check")).containsEntry("matched", true);
        assertThat((Map<String, Object>) nodeResult(matched, "highEnd").get("output"))
                .containsEntry("outcome", "SUCCEEDED");

        Map<String, Object> unmatched = engine.run(definition, Map.of("severity", "low"), Map.of());
        assertSimulated(unmatched);
        assertThat(nodeIds(unmatched)).containsExactly("start", "check", "act", "lowEnd");
        assertThat(nodeResult(unmatched, "check")).containsEntry("matched", false);
        assertThat((Map<String, Object>) nodeResult(unmatched, "lowEnd").get("output"))
                .containsEntry("outcome", "FAILED");
    }

    @Test
    @SuppressWarnings("unchecked")
    void switchResolvesCasePortAndDeclaredDefaultPort() {
        String definition = """
                {
                  "schemaVersion": "soar.playbook",
                  "entryNodeId": "start",
                  "nodes": [
                    {"id": "start", "type": "START"},
                    {"id": "route", "type": "SWITCH", "expression": "severity", "cases": [
                      {"value": "high", "port": "critical"},
                      {"value": "low", "port": "minor"}
                    ]},
                    {"id": "critEnd", "type": "END", "outcome": "SUCCEEDED"},
                    {"id": "minorEnd", "type": "END", "outcome": "SUPPRESSED"},
                    {"id": "defaultEnd", "type": "END", "outcome": "FAILED"}
                  ],
                  "edges": [
                    {"from": "start", "to": "route"},
                    {"from": "route", "to": "critEnd", "port": "critical"},
                    {"from": "route", "to": "minorEnd", "port": "minor"},
                    {"from": "route", "to": "defaultEnd", "port": "default"}
                  ]
                }
                """;

        Map<String, Object> matched = engine.run(definition, Map.of("severity", "high"), Map.of());
        assertSimulated(matched);
        assertThat(nodeIds(matched)).containsExactly("start", "route", "critEnd");
        Map<String, Object> route = nodeResult(matched, "route");
        assertThat(route).containsEntry("value", "high").containsEntry("port", "critical");

        Map<String, Object> fallback = engine.run(definition, Map.of("severity", "medium"), Map.of());
        assertSimulated(fallback);
        assertThat(nodeIds(fallback)).containsExactly("start", "route", "defaultEnd");
        assertThat(nodeResult(fallback, "route")).containsEntry("port", "default");
    }

    @Test
    @SuppressWarnings("unchecked")
    void parallelPlansBranchesAndJumpsToCommonJoin() {
        Map<String, Object> output = engine.run("""
                {
                  "schemaVersion": "soar.playbook",
                  "entryNodeId": "start",
                  "nodes": [
                    {"id": "start", "type": "START"},
                    {"id": "fork", "type": "PARALLEL"},
                    {"id": "branchA", "type": "ACTION", "actionRef": "socp.alert/get"},
                    {"id": "branchB", "type": "ACTION", "actionRef": "socp.alert/add-note"},
                    {"id": "sync", "type": "JOIN", "strategy": "ALL_SUCCESS"},
                    {"id": "end", "type": "END", "outcome": "SUCCEEDED"}
                  ],
                  "edges": [
                    {"from": "start", "to": "fork"},
                    {"from": "fork", "to": "branchA", "port": "branch-a"},
                    {"from": "fork", "to": "branchB", "port": "branch-b"},
                    {"from": "branchA", "to": "sync"},
                    {"from": "branchB", "to": "sync"},
                    {"from": "sync", "to": "end"}
                  ]
                }
                """, Map.of(), Map.of());

        assertSimulated(output);
        assertThat(nodeIds(output)).containsExactly("start", "fork", "sync", "end");
        assertThat((Map<String, Object>) nodeResult(output, "fork").get("output"))
                .containsEntry("planned", true)
                .containsEntry("branches", 2)
                .containsEntry("joinNodeId", "sync");
        assertThat((Map<String, Object>) nodeResult(output, "sync").get("output"))
                .containsEntry("planned", true);
        assertThat((Map<String, Object>) nodeResult(output, "end").get("output"))
                .containsEntry("outcome", "SUCCEEDED");
    }

    @Test
    @SuppressWarnings("unchecked")
    void forEachPlansBoundedIterationsAndTakesDonePort() {
        Map<String, Object> output = engine.run("""
                {
                  "schemaVersion": "soar.playbook",
                  "entryNodeId": "start",
                  "nodes": [
                    {"id": "start", "type": "START"},
                    {"id": "loop", "type": "FOREACH",
                     "config": {"itemsPath": "alertIds"},
                     "limits": {"concurrency": 2, "maxItems": 3}},
                    {"id": "body", "type": "ACTION", "actionRef": "socp.alert/get"},
                    {"id": "end", "type": "END", "outcome": "SUCCEEDED"}
                  ],
                  "edges": [
                    {"from": "start", "to": "loop"},
                    {"from": "loop", "to": "body", "port": "body"},
                    {"from": "loop", "to": "end", "port": "done"},
                    {"from": "body", "to": "end"}
                  ]
                }
                """, Map.of("alertIds", List.of("a-1", "a-2", "a-3")), Map.of());

        assertSimulated(output);
        assertThat(nodeIds(output)).containsExactly("start", "loop", "end");
        Map<String, Object> loop = nodeResult(output, "loop");
        assertThat((Map<String, Object>) loop.get("output"))
                .containsEntry("planned", true)
                .containsEntry("iterations", 3)
                .containsEntry("concurrency", 2);
        assertThat((String) loop.get("warning")).contains("bounded plan");
    }

    @Test
    @SuppressWarnings("unchecked")
    void setVariableWritesVarsNamespaceAndFeedsFollowingCondition() {
        Map<String, Object> output = engine.run("""
                {
                  "schemaVersion": "soar.playbook",
                  "entryNodeId": "start",
                  "nodes": [
                    {"id": "start", "type": "START"},
                    {"id": "flag", "type": "SET_VARIABLE",
                     "config": {"name": "vars.enriched", "value": "yes"}},
                    {"id": "check", "type": "CONDITION", "expression": "enriched == \\"yes\\""},
                    {"id": "okEnd", "type": "END", "outcome": "SUCCEEDED"},
                    {"id": "failEnd", "type": "END", "outcome": "FAILED"}
                  ],
                  "edges": [
                    {"from": "start", "to": "flag"},
                    {"from": "flag", "to": "check", "port": "success"},
                    {"from": "check", "to": "okEnd", "port": "true"},
                    {"from": "check", "to": "failEnd", "port": "false"}
                  ]
                }
                """, Map.of(), Map.of());

        assertSimulated(output);
        assertThat(nodeIds(output)).containsExactly("start", "flag", "check", "okEnd");
        assertThat((Map<String, Object>) nodeResult(output, "flag").get("output"))
                .containsEntry("name", "enriched")
                .containsEntry("value", "yes")
                .containsEntry("simulated", true);
        assertThat(nodeResult(output, "check")).containsEntry("matched", true);
        Map<String, Object> variables = (Map<String, Object>) output.get("variables");
        assertThat(variables).containsEntry("enriched", "yes");
    }

    @Test
    void cyclicDefinitionTerminatesWithinStepBound() {
        // The loop -> hop -> loop cycle is legal because the cycle segment
        // contains a bounded FOREACH node; the dry-run walk must still stop
        // instead of spinning to the 500-step ceiling.
        Map<String, Object> output = engine.run("""
                {
                  "schemaVersion": "soar.playbook",
                  "entryNodeId": "start",
                  "nodes": [
                    {"id": "start", "type": "START"},
                    {"id": "loop", "type": "FOREACH",
                     "config": {"itemsPath": "alertIds"},
                     "limits": {"concurrency": 1, "maxItems": 1}},
                    {"id": "hop", "type": "ACTION", "actionRef": "socp.alert/get"},
                    {"id": "tail", "type": "ACTION", "actionRef": "socp.alert/get"},
                    {"id": "end", "type": "END", "outcome": "SUCCEEDED"}
                  ],
                  "edges": [
                    {"from": "start", "to": "loop"},
                    {"from": "loop", "to": "hop", "port": "done"},
                    {"from": "loop", "to": "tail", "port": "body"},
                    {"from": "hop", "to": "loop"},
                    {"from": "tail", "to": "end"}
                  ]
                }
                """, Map.of("alertIds", List.of("a-1")), Map.of());

        assertSimulated(output);
        assertThat(nodeIds(output)).containsExactly("start", "loop", "hop");
        int steps = (Integer) output.get("steps");
        assertThat(steps).isLessThanOrEqualTo(SoarDefinitionValidator.MAX_NODE_EXECUTIONS);
        assertThat(steps).isLessThan(10);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> results(Map<String, Object> output) {
        return (List<Map<String, Object>>) output.get("nodes");
    }

    private static List<String> nodeIds(Map<String, Object> output) {
        List<String> ids = new ArrayList<>();
        for (Map<String, Object> node : results(output)) ids.add((String) node.get("nodeId"));
        return ids;
    }

    private static Map<String, Object> nodeResult(Map<String, Object> output, String nodeId) {
        return results(output).stream()
                .filter(node -> nodeId.equals(node.get("nodeId")))
                .findFirst()
                .orElseThrow();
    }

    private static void assertSimulated(Map<String, Object> output) {
        assertThat(output)
                .containsEntry("mode", "DRY_RUN")
                .containsEntry("sideEffectsSuppressed", true);
        assertThat(output.get("status")).isNotEqualTo("SUCCEEDED");
        assertThat(output.get("status")).isEqualTo("SIMULATED");
        for (Map<String, Object> node : results(output)) {
            assertThat(node)
                    .containsEntry("status", "SIMULATED")
                    .containsEntry("sideEffectsSuppressed", true);
        }
    }
}
