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
    @MockitoBean private com.socp.incident.web.service.CaseAlarmAssociationService associations;
    @MockitoBean
    private com.socp.incident.web.service.CaseWorkspaceService workspace;

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
    void getReturnsTheCaseDirectlyAndMissingIsNotFound() throws Exception {
        Case incident = Case.create("SSH investigation", "203.0.113.10", "HIGH", "analyst");
        given(service.get(incident.id())).willReturn(incident);

        mvc.perform(get("/api/v1/incidents/{id}", incident.id()).header("Authorization", BEARER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(incident.id()))
                .andExpect(jsonPath("$.data.found").doesNotExist());
        mvc.perform(get("/api/v1/incidents/{id}", "missing").header("Authorization", BEARER))
                .andExpect(status().isNotFound());
    }

    @Test
    void reverseLooksUpCaseByAlarmWithoutScanningAListPage() throws Exception {
        Case incident = Case.create("SSH investigation", "203.0.113.10", "HIGH", "analyst");
        given(service.findByAlarmId("alarm-outside-page")).willReturn(incident);

        mvc.perform(get("/api/v1/incidents/by-alarm")
                        .header("Authorization", BEARER)
                        .param("alarmId", "alarm-outside-page"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(incident.id()));
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
        given(workspace.change(org.mockito.ArgumentMatchers.eq("missing"), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any()))
                .willThrow(new com.socp.platform.error.exception.ApiException(404, "未找到案件 missing"));

        mvc.perform(post("/api/v1/incidents/{id}/status", "missing")
                        .header("Authorization", BEARER)
                        .header("X-Role", "analyst")
                        .param("status", "RESOLVED").param("expectedVersion", "0").param("idempotencyKey", "close-key"))
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
    @Test
    void mineQueueUsesAuthenticatedPrincipalAndRejectsUnknownQueue() throws Exception {
        AuthenticatedIdentityContext.set(new AuthenticatedIdentity("alice", "tenant-a", "analyst",
                java.util.Set.of(), java.util.Set.of(), AuthenticatedIdentity.Kind.USER));
        try {
            given(service.queue(1, 20, "", "", "mine", "dev-user")).willReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));
            mvc.perform(get("/api/v1/incidents").header("Authorization", BEARER).header("X-Role", "analyst").param("size", "20").param("queue", "mine").param("actor", "bob"))
                    .andExpect(status().isOk());
            verify(service).queue(1, 20, "", "", "mine", "dev-user");
            mvc.perform(get("/api/v1/incidents").header("Authorization", BEARER).header("X-Role", "analyst").param("queue", "everyone")).andExpect(status().isBadRequest());
        } finally { AuthenticatedIdentityContext.clear(); }
    }

    @Test
    void jsonNoteUsesBoundedBodyAndTrustedActorRatherThanAuthorParameter() throws Exception {
        AuthenticatedIdentityContext.set(new AuthenticatedIdentity("alice", "tenant-a", "analyst",
                java.util.Set.of(), java.util.Set.of(), AuthenticatedIdentity.Kind.USER));
        try {
            given(workspace.note(org.mockito.ArgumentMatchers.eq("case-1"), org.mockito.ArgumentMatchers.eq("dev-user"), org.mockito.ArgumentMatchers.any()))
                    .willReturn(Map.of("changed", true));
            mvc.perform(post("/api/v1/incidents/case-1/notes").header("Authorization", BEARER).header("X-Role", "analyst").param("author", "bob")
                            .contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"checked\",\"idempotencyKey\":\"note-key\"}"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.changed").value(true));
            verify(workspace).note("case-1", "dev-user", new com.socp.incident.web.api.request.CaseNoteRequest("checked", "note-key"));
            mvc.perform(post("/api/v1/incidents/case-1/notes").header("Authorization", BEARER).header("X-Role", "analyst").contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(Map.of("content", "x".repeat(16001), "idempotencyKey", "oversized"))))
                    .andExpect(status().isBadRequest());
        } finally { AuthenticatedIdentityContext.clear(); }
    }

    @Test
    void workspaceCommandsRequireVersionAndReplayKey() throws Exception {
        mvc.perform(post("/api/v1/incidents/case-1/changes").header("Authorization", BEARER).header("X-Role", "analyst").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"OPEN\"}")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/incidents/case-1/claim").header("Authorization", BEARER).header("X-Role", "analyst").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":-1,\"idempotencyKey\":\"claim\"}")).andExpect(status().isBadRequest());
        org.mockito.Mockito.verifyNoInteractions(workspace);
    }

    @Test
    void summaryExportMakesTruncationAndEvidenceScopeExplicit() throws Exception {
        Case incident = Case.create("bounded summary", "host-1", "HIGH");
        given(service.getMetadata("case-1")).willReturn(incident);
        given(service.timeline("case-1", 0, 500)).willReturn(Map.of("timeline", List.of(), "total", 501L));
        given(service.alarms("case-1", 0, 500)).willReturn(new PageImpl<>(List.of("alarm-1"), PageRequest.of(0, 500), 1));
        given(service.rules("case-1", 0, 500)).willReturn(new PageImpl<>(List.of(), PageRequest.of(0, 500), 0));
        mvc.perform(get("/api/v1/incidents/case-1/export").header("Authorization", BEARER).header("X-Role", "analyst")).andExpect(status().isOk())
                .andExpect(jsonPath("$.truncated").value(true)).andExpect(jsonPath("$.timelineTotal").value(501))
                .andExpect(jsonPath("$.scope").value(org.hamcrest.Matchers.containsString("raw evidence is not included")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Cache-Control", "private, no-store"));
        mvc.perform(get("/api/v1/incidents/missing/export").header("Authorization", BEARER).header("X-Role", "analyst")).andExpect(status().isNotFound());
    }

    @Test
    void finalExportBatchRetainsFixedOffsetAndDoesNotRepeatEarlierRows() throws Exception {
        var first = java.util.stream.IntStream.range(0, 500).mapToObj(i -> Case.create("case-" + i, "host", "LOW")).toList();
        var last = Case.create("case-500", "host", "LOW");
        given(service.count()).willReturn(501L);
        given(service.page(1, 500, "", "")).willReturn(new PageImpl<>(first, PageRequest.of(0, 500), 501));
        given(service.page(2, 500, "", "")).willReturn(new PageImpl<>(List.of(last), PageRequest.of(1, 500), 501));
        mvc.perform(get("/api/v1/incidents/export").header("Authorization", BEARER).header("X-Role", "analyst").param("limit", "501"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(501))
                .andExpect(jsonPath("$[500].title").value("case-500"));
        verify(service).page(2, 500, "", "");
    }

}
