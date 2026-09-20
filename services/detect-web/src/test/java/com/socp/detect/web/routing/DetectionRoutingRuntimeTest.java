package com.socp.detect.web.routing;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DetectionRoutingRuntimeTest {

    @Test
    void migrationModesRequireOneConsistentInputAndOutputPath() {
        DetectionRoutingRuntime legacy = runtime("legacy", "socp-events", "primary", false);
        assertTrue(legacy.validationErrors().isEmpty());

        DetectionRoutingRuntime shadow = runtime(
                "shadow", "socp-detection-routed-v2", "shadow", true);
        assertTrue(shadow.validationErrors().isEmpty());
        assertFalse(shadow.formalOutput());

        DetectionRoutingRuntime primary = runtime(
                "primary", "socp-detection-routed-v2", "primary", true);
        assertTrue(primary.validationErrors().isEmpty());
        assertTrue(primary.formalOutput());

        DetectionRoutingRuntime invalid = runtime(
                "primary", "socp-events", "primary", true);
        assertFalse(invalid.validationErrors().isEmpty());
        assertEquals("INVALID_DEPLOYMENT", invalid.capabilityStatus(null));
    }

    private static DetectionRoutingRuntime runtime(String mode, String input,
                                                   String output, boolean publisher) {
        return new DetectionRoutingRuntime(mode, "socp-events",
                "socp-detection-routed-v2", input, output, publisher,
                "socp-detect", "socp-detect-router-v2");
    }
}
