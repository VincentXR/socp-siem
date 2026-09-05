package com.socp.soar.web.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.socp.soar.web.definition.SoarDefinitionValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class SoarV2TemplateServiceTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final SoarDefinitionValidator validator = new SoarDefinitionValidator(mapper);

    @Mock
    private SoarV2Service soar;

    private SoarV2TemplateService service;

    @BeforeEach
    void setUp() {
        service = new SoarV2TemplateService(mapper, soar);
    }

    @Test
    void listsAllFiveGoldenScenarioTemplates() {
        List<Map<String, Object>> list = service.list();
        assertNotNull(list);
        assertEquals(5, list.size());

        Set<String> expectedIds = Set.of(
                "credential-leak",
                "false-positive",
                "high-risk-ioc",
                "malicious-endpoint",
                "unknown-remote-result"
        );

        for (Map<String, Object> item : list) {
            String id = String.valueOf(item.get("id"));
            assertTrue(expectedIds.contains(id), "Unexpected template id: " + id);
            assertNotNull(item.get("name"));
            assertNotNull(item.get("description"));
            assertNotNull(item.get("risk"));
            assertNotNull(item.get("eventTypes"));
            assertNotNull(item.get("requiredConnectors"));
        }
    }

    @Test
    void allFiveGoldenTemplatesPassDefinitionValidator() throws Exception {
        var resolver = new org.springframework.core.io.support.PathMatchingResourcePatternResolver();
        var resources = resolver.getResources("classpath:/soar/templates/*.json");
        assertEquals(5, resources.length);

        for (var resource : resources) {
            try (var in = resource.getInputStream()) {
                var node = mapper.readTree(in);
                String definition = node.path("definition").toString();
                assertFalse(definition.isBlank());
                var validationResult = validator.validate(definition);
                assertTrue(validationResult.valid(),
                        "Template " + node.path("id").asText() + " failed validation: " + validationResult.errors());
            }
        }
    }

    @Test
    void installCreatesUnpublishedTenantDraft() {
        given(soar.createPlaybook(anyString(), anyString(), any()))
                .willReturn(Map.of("id", "pb-test-1", "name", "High-risk IOC triage", "draftVersion", 1));
        given(soar.saveDraft(anyString(), anyInt(), anyString(), anyString(), isNull()))
                .willReturn(Map.of("playbookId", "pb-test-1", "version", 1, "status", "DRAFT"));

        Map<String, Object> installed = service.install("high-risk-ioc");
        assertNotNull(installed);
        assertEquals("high-risk-ioc", installed.get("templateId"));
        assertEquals(false, installed.get("published"));
        assertEquals(false, installed.get("ruleEnabled"));
        assertNotNull(installed.get("playbook"));
        assertNotNull(installed.get("draft"));
    }
}
