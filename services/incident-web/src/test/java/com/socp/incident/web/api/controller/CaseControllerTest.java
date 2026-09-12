package com.socp.incident.web.api.controller;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.socp.incident.web.domain.Case;
import com.socp.incident.web.service.CaseService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(CaseController.class)
@AutoConfigureMockMvc(addFilters = false)
@TestPropertySource(properties = {"socp.security.dev-bypass=true"})
class CaseControllerTest {

    private static final String BEARER = "Bearer test-token";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    @MockitoBean
    private CaseService service;

    @Test
    void createRejectsBlankTitleBeforeCallingService() throws Exception {
        mvc.perform(post("/api/v1/incidents")
                        .header("Authorization", BEARER)
                        .header("X-Role", "analyst")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "title", " ",
                                "entity", "203.0.113.10",
                                "severity", "HIGH"))))
                .andExpect(status().isBadRequest());

        verify(service, org.mockito.Mockito.never())
                .create(org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any());
    }

    @Test
    void createWrapsCaseInStableResponseEnvelope() throws Exception {
        Case created = Case.create("SSH investigation", "203.0.113.10", "HIGH", "analyst");
        given(service.create("SSH investigation", "203.0.113.10", "HIGH", "analyst"))
                .willReturn(created);

        mvc.perform(post("/api/v1/incidents")
                        .header("Authorization", BEARER)
                        .header("X-Role", "analyst")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "title", "SSH investigation",
                                "entity", "203.0.113.10",
                                "severity", "HIGH",
                                "assignee", "analyst"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.case.title").value("SSH investigation"))
                .andExpect(jsonPath("$.data.case.status").value("OPEN"))
                .andExpect(jsonPath("$.data.case.assignee").value("analyst"));
    }

    @Test
    void listReturnsPagedEnvelope() throws Exception {
        Case created = Case.create("SSH investigation", "203.0.113.10", "HIGH", "analyst");
        given(service.list()).willReturn(List.of(created));

        mvc.perform(get("/api/v1/incidents")
                        .header("Authorization", BEARER)
                        .header("X-Role", "analyst"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.size").value(500))
                .andExpect(jsonPath("$.data.totalPages").value(1))
                .andExpect(jsonPath("$.data.items[0].title").value("SSH investigation"));
    }

    @Test
    void listRejectsSizeAboveConfiguredLimit() throws Exception {
        mvc.perform(get("/api/v1/incidents")
                        .header("Authorization", BEARER)
                        .header("X-Role", "analyst")
                        .param("page", "1")
                        .param("size", "501"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void timelineReturnsOneBasedPagedEnvelope() throws Exception {
        Case created = Case.create("SSH investigation", "203.0.113.10", "HIGH", "analyst");
        given(service.timeline("case-1", 0, 50)).willReturn(Map.of(
                "caseId", "case-1",
                "page", 0,
                "size", 50,
                "total", 3L,
                "timeline", created.timeline()));

        mvc.perform(get("/api/v1/incidents/{id}/timeline", "case-1")
                        .header("Authorization", BEARER)
                        .param("page", "1")
                        .param("size", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.size").value(50))
                .andExpect(jsonPath("$.data.total").value(3))
                .andExpect(jsonPath("$.data.totalPages").value(1));
    }
}
