package com.socp.incident.web.api.controller;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.socp.incident.web.domain.Case;
import com.socp.incident.web.service.CaseService;
import com.socp.platform.tenant.context.AuthenticatedIdentity;
import com.socp.platform.tenant.context.AuthenticatedIdentityContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

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

    @Autowired
    private CaseController controller;

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
        given(service.page(1, 500, "", ""))
                .willReturn(new PageImpl<>(List.of(created), PageRequest.of(0, 500), 1));

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
    void listRejectsZeroSize() throws Exception {
        mvc.perform(get("/api/v1/incidents")
                        .header("Authorization", BEARER)
                        .header("X-Role", "analyst")
                        .param("page", "1")
                        .param("size", "0"))
                .andExpect(status().isBadRequest());
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
    void listRejectsAnUnboundedSearchQuery() throws Exception {
        mvc.perform(get("/api/v1/incidents")
                        .header("Authorization", BEARER)
                        .header("X-Role", "analyst")
                        .param("q", "x".repeat(129)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void exportStreamsBoundedPagedCases() throws Exception {
        Case created = Case.create("SSH investigation", "203.0.113.10", "HIGH", "analyst");
        given(service.count()).willReturn(1L);
        given(service.page(1, 100, "", ""))
                .willReturn(new PageImpl<>(List.of(created), PageRequest.of(0, 100), 1));

        mvc.perform(get("/api/v1/incidents/export")
                        .header("Authorization", BEARER)
                        .header("X-Role", "analyst")
                        .param("limit", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].title").value("SSH investigation"));

        verify(service).count();
        verify(service).page(1, 100 > CaseController.EXPORT_BATCH_SIZE
                ? CaseController.EXPORT_BATCH_SIZE : 100, "", "");
    }

    @Test
    void exportRejectsAnUnboundedLimit() throws Exception {
        mvc.perform(get("/api/v1/incidents/export")
                        .header("Authorization", BEARER)
                        .header("X-Role", "analyst")
                        .param("limit", String.valueOf(CaseController.EXPORT_MAX_LIMIT + 1)))
                .andExpect(status().isBadRequest());

        verify(service, org.mockito.Mockito.never()).count();
    }

    @Test
    void exportReturnsPayloadTooLargeBeforeWritingHeaders() throws Exception {
        given(service.count()).willReturn((long) CaseController.EXPORT_MAX_LIMIT + 1);

        mvc.perform(get("/api/v1/incidents/export")
                        .header("Authorization", BEARER)
                        .header("X-Role", "analyst")
                        .param("limit", String.valueOf(CaseController.EXPORT_MAX_LIMIT)))
                .andExpect(status().isPayloadTooLarge());

        verify(service, org.mockito.Mockito.never()).page(org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void statusForMissingCaseReturnsNotFoundEnvelopeInsteadOfDataError() throws Exception {
        given(service.setStatus("missing", "RESOLVED", null))
                .willThrow(new com.socp.platform.error.exception.ApiException(404, "未找到案件 missing"));

        mvc.perform(post("/api/v1/incidents/{id}/status", "missing")
                        .header("Authorization", BEARER)
                        .header("X-Role", "analyst")
                        .param("status", "RESOLVED"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404))
                .andExpect(jsonPath("$.message").value("未找到案件 missing"))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    void noteForMissingCaseReturnsNotFoundEnvelopeInsteadOfDataError() throws Exception {
        given(service.addNote(org.mockito.ArgumentMatchers.eq("missing"),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.eq("checked"),
                org.mockito.ArgumentMatchers.isNull()))
                .willThrow(new com.socp.platform.error.exception.ApiException(404, "未找到案件 missing"));

        mvc.perform(post("/api/v1/incidents/{id}/notes", "missing")
                        .header("Authorization", BEARER)
                        .header("X-Role", "analyst")
                        .param("author", "analyst")
                        .param("content", "checked"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404))
                .andExpect(jsonPath("$.data").doesNotExist());
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

    @Test
    void noteAttributesToAuthenticatedSubjectNotTheRequestBodyAuthor() {
        given(service.addNote(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any())).willReturn(Map.of());
        AuthenticatedIdentityContext.set(new AuthenticatedIdentity("analyst-9", "tenant-a", "analyst",
                java.util.Set.of(), java.util.Set.of(), AuthenticatedIdentity.Kind.USER));
        try {
            controller.note("case-1", "someone-else", "checked", null);
            verify(service).addNote("case-1", "analyst-9", "checked", null);
        } finally {
            AuthenticatedIdentityContext.clear();
        }
    }

    @Test
    void noteFromServiceIdentityKeepsTheDelegatedAuthor() {
        given(service.addNote(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any())).willReturn(Map.of());
        AuthenticatedIdentityContext.set(new AuthenticatedIdentity("ai-assistant", "tenant-a", "service",
                java.util.Set.of(), java.util.Set.of(), AuthenticatedIdentity.Kind.SERVICE));
        try {
            controller.note("case-1", "ai-investigation", "summary", "inv-7");
            verify(service).addNote("case-1", "ai-investigation", "summary", "inv-7");
        } finally {
            AuthenticatedIdentityContext.clear();
        }
    }
}
