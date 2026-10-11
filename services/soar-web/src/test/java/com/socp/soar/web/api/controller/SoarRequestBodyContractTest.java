package com.socp.soar.web.api.controller;

import com.socp.soar.web.api.request.ApprovalDecisionRequest;
import com.socp.soar.web.api.request.PlaybookExecutionRequest;
import com.socp.soar.web.api.request.UnknownResolutionRequest;
import com.socp.soar.web.api.request.UpdatePlaybookRequest;
import jakarta.validation.Valid;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;

import java.lang.reflect.Parameter;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Checks compiled handler annotations, independently of source formatting and the static gate. */
class SoarRequestBodyContractTest {
    private static final Set<String> EXTENSION_ROUTES = Set.of(
            "PATCH /automation-rules/{id}", "POST /automation-rules/evaluate",
            "POST /automation-rules/test", "POST /events/evaluate");

    @Test
    void highRiskHandlersRetainTypedValidatedBodiesAndOptionalMetadataUpdates() {
        Map<String, Parameter> bodies = requestBodies();
        assertBody(bodies, "PATCH /playbooks/{id}", UpdatePlaybookRequest.class, true, false);
        assertBody(bodies, "POST /node-runs/{id}/resolve-unknown", UnknownResolutionRequest.class, true, true);
        assertBody(bodies, "POST /approvals/{id}/decisions", ApprovalDecisionRequest.class, true, true);
        assertBody(bodies, "POST /manual-tasks/{id}/complete", PlaybookExecutionRequest.class, false, true);
    }

    @Test
    void dynamicMapBodiesBelongToNamedJsonExtensionRoutes() {
        requestBodies().forEach((route, parameter) -> {
            if (Map.class.isAssignableFrom(parameter.getType())) {
                assertTrue(EXTENSION_ROUTES.contains(route), "Unexpected dynamic request body: " + route);
            }
        });
    }

    private static void assertBody(Map<String, Parameter> bodies, String route, Class<?> type,
                                   boolean validated, boolean required) {
        Parameter parameter = bodies.get(route);
        assertNotNull(parameter, "Missing request body route: " + route);
        assertEquals(type, parameter.getType(), route);
        if (validated) assertTrue(parameter.isAnnotationPresent(Valid.class), route + " needs @Valid");
        assertEquals(required, parameter.getAnnotation(RequestBody.class).required(), route);
    }

    private static Map<String, Parameter> requestBodies() {
        Map<String, Parameter> bodies = new HashMap<>();
        for (Class<?> controller : new Class<?>[]{SoarController.class, SoarRunController.class,
                SoarAutomationController.class, SoarConnectorController.class}) {
            for (var method : controller.getDeclaredMethods()) {
                RequestMapping mapping = AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
                if (mapping == null) continue; // Unmapped Java compatibility overloads are not HTTP handlers.
                for (Parameter parameter : method.getParameters()) {
                    if (!parameter.isAnnotationPresent(RequestBody.class)) continue;
                    assertEquals(1, mapping.method().length, method.toString());
                    assertTrue(mapping.path().length > 0, method.toString());
                    for (String path : mapping.path()) {
                        String route = mapping.method()[0] + " " + path;
                        assertNull(bodies.put(route, parameter), "Duplicate request body route: " + route);
                    }
                }
            }
        }
        return bodies;
    }
}
