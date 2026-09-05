package com.socp.soar.web.definition;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.socp.soar.web.connector.ActionDescriptor;
import com.socp.soar.web.connector.ConnectorDescriptor;
import com.socp.soar.web.connector.SoarConnectorRegistry;
import com.socp.soar.web.domain.v2.DefinitionIssue;
import com.socp.soar.web.domain.v2.DefinitionValidationResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SoarRiskPolicyValidationTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private String definition(String actionRef, Map<String, Object> parameters) {
        StringBuilder parametersJson = new StringBuilder();
        if (parameters == null || parameters.isEmpty()) {
            parametersJson.append("{}");
        } else {
            try {
                parametersJson.append(mapper.writeValueAsString(parameters));
            } catch (Exception failure) {
                throw new IllegalStateException(failure);
            }
        }
        return "{\"schemaVersion\":\"soar.playbook/v2\",\"name\":\"risk\",\"version\":1,"
                + "\"entryNodeId\":\"n1\",\"nodes\":["
                + "{\"id\":\"n1\",\"type\":\"START\"},"
                + "{\"id\":\"n2\",\"type\":\"ACTION\",\"actionRef\":\"" + actionRef + "\","
                + "\"parameters\":" + parametersJson + "},"
                + "{\"id\":\"n3\",\"type\":\"END\",\"outcome\":\"SUCCEEDED\"}],"
                + "\"edges\":[{\"from\":\"n1\",\"to\":\"n2\"},"
                + "{\"from\":\"n2\",\"to\":\"n3\",\"port\":\"success\"}],"
                + "\"limits\":{}}";
    }

    @Test
    void criticalActionIsRejectedAtPublication() {
        ActionDescriptor critical = new ActionDescriptor("set-status", 1, "Set status", "",
                "CRITICAL", "REVERSIBLE", "NATIVE", false, List.of("alert"),
                Map.of(), Map.of());
        SoarConnectorRegistry registry = mock(SoarConnectorRegistry.class);
        when(registry.actionDescriptor(anyString())).thenReturn(Optional.of(critical));
        when(registry.descriptorForAction(anyString())).thenReturn(Optional.of(
                new ConnectorDescriptor("socp.alert", 1, "SOCP Alert", true, List.of(critical))));
        when(registry.canonicalActionRef(anyString())).thenAnswer(invocation -> invocation.getArgument(0));

        SoarDefinitionValidator validator = new SoarDefinitionValidator(mapper, registry);
        DefinitionValidationResult result = validator.validate(
                definition("socp.alert/set-status@1", Map.of("status", "closed")));

        assertFalse(result.valid());
        assertTrue(result.errors().stream().map(DefinitionIssue::code)
                .anyMatch("ACTION_RISK_CRITICAL_FORBIDDEN"::equals), result.errors().toString());
    }

    @Test
    void embeddedCredentialInParametersIsRejected() {
        SoarDefinitionValidator validator = new SoarDefinitionValidator(mapper, null);
        DefinitionValidationResult result = validator.validate(definition(
                "socp.search/search-events@1",
                Map.of("query", "malware", "url", "https://admin:supersecret@int.example/api")));

        assertFalse(result.valid());
        assertTrue(result.errors().stream().map(DefinitionIssue::code)
                .anyMatch("ACTION_EMBEDDED_CREDENTIAL_FORBIDDEN"::equals), result.errors().toString());
    }

    @Test
    void highRiskWithoutGateWarnsButRemainsValid() {
        SoarDefinitionValidator validator = new SoarDefinitionValidator(mapper, null);
        DefinitionValidationResult result = validator.validate(
                definition("endpoint/isolate-host@1", Map.of("host", "10.0.0.5")));

        assertTrue(result.valid(), result.errors().toString());
        assertTrue(result.warnings().stream().map(DefinitionIssue::code)
                .anyMatch("HIGH_RISK_APPROVAL_GATE_RECOMMENDED"::equals), result.warnings().toString());
    }
}
