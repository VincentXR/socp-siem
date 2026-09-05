package com.socp.soar.web.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.socp.platform.tenant.context.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class SoarV2AutomationRuleServiceTest {
    private SoarV2AutomationRuleService service;
    private Method normalize;

    @BeforeEach
    void setUp() throws Exception {
        service = new SoarV2AutomationRuleService(
                mock(com.socp.soar.web.persistence.repository.SoarAutomationRuleRepository.class),
                mock(SoarV2Service.class), new ObjectMapper());
        normalize = SoarV2AutomationRuleService.class.getDeclaredMethod(
                "normalizeEvent", Map.class, String.class);
        normalize.setAccessible(true);
        TenantContext.set("tenant-a");
    }

    @AfterEach
    void tearDown() { TenantContext.clear(); }

    @Test
    @SuppressWarnings("unchecked")
    void normalizesLegacyEventToTypedEnvelopeWithoutChangingTenant() throws Exception {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("id", "alert-1");
        input.put("type", "alert.created");
        input.put("data", Map.of("riskLevel", "HIGH"));

        Map<String, Object> result = (Map<String, Object>) normalize.invoke(service, input, "tenant-a");

        assertEquals("soar.event/v1", result.get("schemaVersion"));
        assertEquals("alert-1", result.get("eventId"));
        assertEquals("alert.created", result.get("eventType"));
        assertEquals("tenant-a", result.get("tenantId"));
        assertEquals("alert-1", ((Map<String, Object>) result.get("subject")).get("id"));
        assertEquals(0, ((Map<String, Object>) result.get("trace")).get("automationDepth"));
        assertTrue(result.containsKey("occurredAt"));
    }

    @Test
    void rejectsUnknownSchemaTenantAndNonIntegerAutomationDepth() {
        Map<String, Object> wrongSchema = Map.of("schemaVersion", "soar.event/v2",
                "eventId", "e", "eventType", "alert.created");
        assertThrows(ResponseStatusException.class,
                () -> invoke(wrongSchema, "tenant-a"));

        Map<String, Object> wrongTenant = Map.of("eventId", "e", "eventType", "alert.created",
                "tenantId", "tenant-b");
        assertThrows(ResponseStatusException.class,
                () -> invoke(wrongTenant, "tenant-a"));

        Map<String, Object> wrongDepth = new LinkedHashMap<>();
        wrongDepth.put("eventId", "e");
        wrongDepth.put("eventType", "alert.created");
        wrongDepth.put("trace", Map.of("automationDepth", "1"));
        assertThrows(ResponseStatusException.class,
                () -> invoke(wrongDepth, "tenant-a"));
    }

    private Object invoke(Map<String, Object> event, String tenant) {
        try { return normalize.invoke(service, event, tenant); }
        catch (ReflectiveOperationException failure) {
            if (failure.getCause() instanceof RuntimeException runtime) throw runtime;
            throw new AssertionError(failure);
        }
    }
}
