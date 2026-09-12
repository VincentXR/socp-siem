package com.socp.soar.web.api.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.socp.platform.error.api.ApiResult;
import com.socp.soar.web.api.request.SaveVersionRequest;
import com.socp.soar.web.service.SoarAutomationRuleService;
import com.socp.soar.web.service.SoarConnectorService;
import com.socp.soar.web.service.SoarService;
import com.socp.soar.web.service.SoarTemplateService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;

/** If-Match / ETag conformance for the version endpoints (RFC 7232). */
@ExtendWith(MockitoExtension.class)
class SoarEtagConformanceTest {

    @Mock
    private SoarService service;
    @Mock
    private SoarAutomationRuleService automationRules;
    @Mock
    private SoarConnectorService connectors;
    @Mock
    private SoarTemplateService templates;

    private final ObjectMapper mapper = new ObjectMapper();

    private SoarController controller() {
        return new SoarController(service, automationRules, connectors, templates);
    }

    @Test
    void versionResponseCarriesWeakEtagFromRowVersion() {
        given(service.getVersion("pb-1", 2))
                .willReturn(Map.of("id", "ver-2", "version", 2, "rowVersion", 7L));

        MockHttpServletResponse response = new MockHttpServletResponse();
        ApiResult<Map<String, Object>> result = controller().version("pb-1", 2, response);

        assertThat(result.data()).containsEntry("version", 2);
        assertThat(response.getHeader("ETag")).isEqualTo("W/\"7\"");
    }

    @Test
    void ifMatchDrivesExpectedRowVersionAndConvertsConflictTo412() throws Exception {
        JsonNode definition = mapper.readTree("{\"schemaVersion\":\"soar.playbook\",\"entryNodeId\":\"s\","
                + "\"nodes\":[{\"id\":\"s\",\"type\":\"START\"}],\"edges\":[]}");
        SaveVersionRequest request = new SaveVersionRequest(definition, null, null);

        given(service.saveDraft(anyString(), anyInt(), anyString(), anyString(), any()))
                .willThrow(new ResponseStatusException(HttpStatus.CONFLICT,
                        "SOAR_VERSION_CONFLICT draft was changed by another editor"));

        MockHttpServletResponse response = new MockHttpServletResponse();
        assertThatThrownBy(() -> controller().saveDraft("pb-1", 2, request, "\"3\"", response))
                .isInstanceOfSatisfying(ResponseStatusException.class, error -> {
                    assertThat(error.getStatusCode().value()).isEqualTo(412);
                    assertThat(error.getReason()).contains("SOAR_VERSION_CONFLICT");
                });
    }

    @Test
    void putResponseAlsoCarriesTheEtag() throws Exception {
        JsonNode definition = mapper.readTree("{\"schemaVersion\":\"soar.playbook\",\"entryNodeId\":\"s\","
                + "\"nodes\":[{\"id\":\"s\",\"type\":\"START\"}],\"edges\":[]}");
        SaveVersionRequest request = new SaveVersionRequest(definition, null, null);
        given(service.saveDraft(anyString(), anyInt(), anyString(), anyString(), any()))
                .willReturn(Map.of("id", "ver-2", "version", 2, "rowVersion", 4L));

        MockHttpServletResponse response = new MockHttpServletResponse();
        ApiResult<Map<String, Object>> result = controller().saveDraft("pb-1", 2, request, null, response);

        assertThat(result.data()).containsEntry("rowVersion", 4L);
        assertThat(response.getHeader("ETag")).isEqualTo("W/\"4\"");
    }
}
