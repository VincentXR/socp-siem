package com.socp.detect.web.routing;

import com.socp.detect.web.persistence.store.DetectionContentCatalog;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DetectionRoutingPlanTest {

    @Test
    @SuppressWarnings("unchecked")
    void activePackHasExecutableBoundedCrossDimensionPlan() {
        List<Map<String, Object>> documents = new ArrayList<>();
        List<Map<String, Object>> manifestRules =
                (List<Map<String, Object>>) DetectionContentCatalog.manifest().get("rules");
        for (Map<String, Object> item : manifestRules) {
            documents.add(DetectionContentCatalog.enrich(
                    (Map<String, Object>) item.get("spec")));
        }

        DetectionRoutingPlan plan = DetectionRoutingPlan.compile(documents, 8);

        assertTrue(plan.supported(), () -> String.join("; ", plan.allErrors()));
        assertEquals(21, plan.rules().size(), "all ACTIVE stateful packaged rules must be audited");
        Set<String> dimensions = new LinkedHashSet<>();
        plan.rules().forEach(rule -> dimensions.add(rule.dimension()));
        assertEquals(Set.of("src_ip", "host", "dst_ip", "user"), dimensions);
        assertEquals(4, plan.statefulDimensionCount());
        assertEquals(5, plan.maximumFanOut(),
                "one stateless/control delivery plus four unique state dimensions is the hard pack bound");
        assertTrue(plan.rules().stream().allMatch(rule -> "SUPPORTED".equals(rule.status())));
        assertTrue(plan.rules().stream().anyMatch(rule ->
                "CORR-FAIL-SUDO".equals(rule.ruleId()) && "user".equals(rule.dimension())));
        assertTrue(plan.rules().stream().anyMatch(rule ->
                "BASELINE-AUTH-VOLUME".equals(rule.ruleId()) && "user".equals(rule.dimension())));
        assertTrue(plan.rules().stream().anyMatch(rule ->
                "UEBA-NEW-GEO".equals(rule.ruleId()) && "user".equals(rule.dimension())));
    }

    @Test
    @SuppressWarnings("unchecked")
    void deploymentBoundFailureIsExplicitInsteadOfSilentlyDroppingDimensions() {
        List<Map<String, Object>> documents = new ArrayList<>();
        List<Map<String, Object>> manifestRules =
                (List<Map<String, Object>>) DetectionContentCatalog.manifest().get("rules");
        for (Map<String, Object> item : manifestRules) {
            documents.add(DetectionContentCatalog.enrich(
                    (Map<String, Object>) item.get("spec")));
        }

        DetectionRoutingPlan plan = DetectionRoutingPlan.compile(documents, 3);

        assertFalse(plan.supported());
        assertTrue(plan.allErrors().stream().anyMatch(error ->
                error.contains("requires 4 routing dimensions") && error.contains("max is 3")));
    }

    @Test
    void invalidActiveGroupingIsReportedPerRule() {
        Map<String, Object> invalid = Map.of(
                "id", "BAD-COMPOSITE",
                "name", "bad",
                "type", "threshold",
                "status", "ACTIVE",
                "groupBy", "user+bad field",
                "routingField", "user+bad field",
                "dataSources", List.of("auth"),
                "version", "1");

        DetectionRoutingPlan plan = DetectionRoutingPlan.compile(List.of(invalid), 8);

        assertFalse(plan.supported());
        assertEquals("UNSUPPORTED", plan.rules().getFirst().status());
        assertTrue(plan.rules().getFirst().reason().contains("invalid routing dimension"));
    }
}
