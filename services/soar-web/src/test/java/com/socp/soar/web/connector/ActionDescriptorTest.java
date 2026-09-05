package com.socp.soar.web.connector;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ActionDescriptorTest {

    @Test
    void legacyConstructorsKeepSafeDefaults() {
        ActionDescriptor twelve = new ActionDescriptor("get", 1, "Get", "desc", "LOW", "NONE",
                "NONE", false, List.of("alert"), Map.of(), Map.of(), List.of("soar:execute"));
        assertEquals(60, twelve.requestTimeoutSeconds());
        assertEquals(3, twelve.retryCap());
        assertEquals(10L * 1024 * 1024, twelve.payloadCapBytes());
        assertTrue(twelve.sensitiveOutputFields().isEmpty());
        assertFalse(twelve.supportsReconcile());
        assertFalse(twelve.supportsCompensate());

        ActionDescriptor eleven = new ActionDescriptor("get", 1, "Get", "desc", "LOW", "NONE",
                "NONE", false, List.of("alert"), Map.of(), Map.of());
        assertEquals("soar:execute", eleven.requiredPermissions().get(0));
        assertEquals(60, eleven.requestTimeoutSeconds());
    }

    @Test
    void fullMetadataIsClampedToEngineBounds() {
        ActionDescriptor action = new ActionDescriptor("isolate-host", 1, "Isolate", "desc", "HIGH",
                "REVERSIBLE", "NATIVE", true, List.of("host"),
                Map.of("type", "object"), Map.of("type", "object"), List.of("soar:execute", "soar:approve"),
                900, 25, 200L * 1024 * 1024, List.of("vmName"), true, true);
        assertEquals(300, action.requestTimeoutSeconds());
        assertEquals(10, action.retryCap());
        assertEquals(100L * 1024 * 1024, action.payloadCapBytes());
        assertEquals(List.of("vmName"), action.sensitiveOutputFields());
        assertTrue(action.supportsReconcile());
        assertTrue(action.supportsCompensate());
    }
}
