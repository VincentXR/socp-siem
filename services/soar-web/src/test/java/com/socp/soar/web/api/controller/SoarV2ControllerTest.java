package com.socp.soar.web.api.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.socp.soar.web.domain.v2.DefinitionValidationResult;
import com.socp.soar.web.service.SoarV2AutomationRuleService;
import com.socp.soar.web.service.SoarV2ConnectorService;
import com.socp.soar.web.service.SoarV2Service;
import com.socp.soar.web.service.SoarV2TemplateService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(SoarV2Controller.class)
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "socp.security.dev-bypass=true",
        "socp.security.service-secret=test-service-secret"
})
class SoarV2ControllerTest {

    private static final String BEARER = "Bearer test-token";
    private static final String ROLE_ADMIN = "admin";
    private static final String ROLE_VIEWER = "viewer";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    @MockitoBean
    private SoarV2Service service;

    @MockitoBean
    private SoarV2AutomationRuleService automationRules;

    @MockitoBean
    private SoarV2ConnectorService connectors;

    @MockitoBean
    private SoarV2TemplateService templates;

    @Test
    void listPlaybooksReturnsPagedPayload() throws Exception {
        given(service.listPlaybooks(any(), isNull(), isNull(), isNull(), isNull()))
                .willReturn(new PageImpl<>(List.of(Map.of("id", "pb-1", "name", "Triage IOC", "status", "ACTIVE")),
                        PageRequest.of(0, 20), 1));

        mvc.perform(get("/api/v2/playbooks")
                        .header(HttpHeaders.AUTHORIZATION, BEARER)
                        .header("X-Role", ROLE_ADMIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.items[0].id").value("pb-1"))
                .andExpect(jsonPath("$.data.items[0].name").value("Triage IOC"));
    }

    @Test
    void createPlaybookReturns201ForAdminAndRejectsViewer() throws Exception {
        given(service.createPlaybook(eq("New Playbook"), anyString(), any()))
                .willReturn(Map.of("id", "pb-2", "name", "New Playbook", "status", "ACTIVE"));

        Map<String, Object> req = Map.of("name", "New Playbook", "description", "desc", "tags", List.of("network"));

        mvc.perform(post("/api/v2/playbooks")
                        .header(HttpHeaders.AUTHORIZATION, BEARER)
                        .header("X-Role", ROLE_VIEWER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(req)))
                .andExpect(status().isForbidden());

        mvc.perform(post("/api/v2/playbooks")
                        .header(HttpHeaders.AUTHORIZATION, BEARER)
                        .header("X-Role", ROLE_ADMIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.id").value("pb-2"));
    }

    @Test
    void getPlaybookAndPatchPlaybookWork() throws Exception {
        given(service.getPlaybook("pb-1")).willReturn(Map.of("id", "pb-1", "name", "Triage IOC"));
        given(service.updatePlaybook(eq("pb-1"), eq("Updated Triage"), any(), any(), any(), any()))
                .willReturn(Map.of("id", "pb-1", "name", "Updated Triage"));

        mvc.perform(get("/api/v2/playbooks/pb-1")
                        .header(HttpHeaders.AUTHORIZATION, BEARER)
                        .header("X-Role", ROLE_ADMIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value("pb-1"));

        mvc.perform(patch("/api/v2/playbooks/pb-1")
                        .header(HttpHeaders.AUTHORIZATION, BEARER)
                        .header("X-Role", ROLE_ADMIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("name", "Updated Triage"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("Updated Triage"));
    }

    @Test
    void playbookVersionsWorkflowOperations() throws Exception {
        given(service.listVersions("pb-1")).willReturn(List.of(Map.of("version", 1, "status", "DRAFT")));
        given(service.getVersion("pb-1", 1)).willReturn(Map.of("version", 1, "definition", "{}"));
        given(service.saveDraft(eq("pb-1"), eq(1), anyString(), anyString(), isNull()))
                .willReturn(Map.of("version", 1, "status", "DRAFT"));
        given(service.validateVersion("pb-1", 1))
                .willReturn(new DefinitionValidationResult(true, List.of(), List.of(), "soar.playbook/v2", "hash1", 5, 2, 0));
        given(service.publish("pb-1", 1)).willReturn(Map.of("version", 1, "status", "PUBLISHED"));
        given(service.deprecate("pb-1", 1)).willReturn(Map.of("version", 1, "status", "DEPRECATED"));

        mvc.perform(get("/api/v2/playbooks/pb-1/versions")
                        .header(HttpHeaders.AUTHORIZATION, BEARER)
                        .header("X-Role", ROLE_ADMIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].version").value(1));

        mvc.perform(put("/api/v2/playbooks/pb-1/versions/1")
                        .header(HttpHeaders.AUTHORIZATION, BEARER)
                        .header("X-Role", ROLE_ADMIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("definition", "{}", "layout", "{}"))))
                .andExpect(status().isOk());

        mvc.perform(post("/api/v2/playbooks/pb-1/versions/1/validate")
                        .header(HttpHeaders.AUTHORIZATION, BEARER)
                        .header("X-Role", ROLE_ADMIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.valid").value(true));

        mvc.perform(post("/api/v2/playbooks/pb-1/versions/1/publish")
                        .header(HttpHeaders.AUTHORIZATION, BEARER)
                        .header("X-Role", ROLE_ADMIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PUBLISHED"));

        mvc.perform(post("/api/v2/playbooks/pb-1/versions/1/deprecate")
                        .header(HttpHeaders.AUTHORIZATION, BEARER)
                        .header("X-Role", ROLE_ADMIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DEPRECATED"));
    }

    @Test
    void postRunsReturns202Accepted() throws Exception {
        given(service.queueManualRun(eq("req-123"), eq("ver-1"), anyMap(), anyMap()))
                .willReturn(Map.of("runId", "run-100", "status", "QUEUED", "duplicate", false));

        Map<String, Object> req = Map.of(
                "requestId", "req-123",
                "playbookVersionId", "ver-1",
                "subject", Map.of("type", "alert", "id", "al-1"),
                "inputs", Map.of("source", "manual")
        );

        mvc.perform(post("/api/v2/runs")
                        .header(HttpHeaders.AUTHORIZATION, BEARER)
                        .header("X-Role", ROLE_ADMIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(req)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.runId").value("run-100"))
                .andExpect(jsonPath("$.data.status").value("QUEUED"));
    }

    @Test
    void runDetailAndLifecycleOperations() throws Exception {
        given(service.getRun("run-100")).willReturn(Map.of("runId", "run-100", "status", "RUNNING"));
        given(service.cancelRun(eq("run-100"), anyString())).willReturn(Map.of("runId", "run-100", "status", "CANCELLING"));
        given(service.retryRun(eq("run-100"), anyString())).willReturn(Map.of("runId", "run-101", "status", "QUEUED"));
        given(service.rerun(eq("run-100"), anyString(), eq(true))).willReturn(Map.of("runId", "run-102", "status", "QUEUED"));

        mvc.perform(get("/api/v2/runs/run-100")
                        .header(HttpHeaders.AUTHORIZATION, BEARER)
                        .header("X-Role", ROLE_ADMIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.runId").value("run-100"));

        mvc.perform(post("/api/v2/runs/run-100/cancel")
                        .header(HttpHeaders.AUTHORIZATION, BEARER)
                        .header("X-Role", ROLE_ADMIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("reason", "analyst cancelled"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANCELLING"));

        mvc.perform(post("/api/v2/runs/run-100/retry")
                        .header(HttpHeaders.AUTHORIZATION, BEARER)
                        .header("X-Role", ROLE_ADMIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("reason", "retry after connection fix"))))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.runId").value("run-101"));

        mvc.perform(post("/api/v2/runs/run-100/rerun")
                        .header(HttpHeaders.AUTHORIZATION, BEARER)
                        .header("X-Role", ROLE_ADMIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("reason", "rerun with confirmation", "confirm", true))))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.runId").value("run-102"));
    }

    @Test
    void resolveUnknownActionOutcome() throws Exception {
        given(service.resolveUnknown(eq("node-1"), eq("CONFIRMED_SUCCEEDED"), anyString(), anyString()))
                .willReturn(Map.of("nodeRunId", "node-1", "resolution", "CONFIRMED_SUCCEEDED"));

        Map<String, Object> req = Map.of(
                "resolution", "CONFIRMED_SUCCEEDED",
                "evidence", "Remote firewall log verified",
                "reason", "IOC rule was committed remotely"
        );

        mvc.perform(post("/api/v2/node-runs/node-1/resolve-unknown")
                        .header(HttpHeaders.AUTHORIZATION, BEARER)
                        .header("X-Role", ROLE_ADMIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.resolution").value("CONFIRMED_SUCCEEDED"));
    }

    @Test
    void approvalsAndDecisions() throws Exception {
        given(service.listApprovals()).willReturn(List.of(Map.of("id", "appr-1", "status", "PENDING")));
        given(service.decideApproval(eq("appr-1"), eq(true), anyString()))
                .willReturn(Map.of("id", "appr-1", "status", "APPROVED"));

        mvc.perform(get("/api/v2/approvals")
                        .header(HttpHeaders.AUTHORIZATION, BEARER)
                        .header("X-Role", ROLE_ADMIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value("appr-1"));

        mvc.perform(post("/api/v2/approvals/appr-1/decisions")
                        .header(HttpHeaders.AUTHORIZATION, BEARER)
                        .header("X-Role", ROLE_ADMIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("decision", "APPROVED", "reason", "Isolation authorized"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("APPROVED"));
    }

    @Test
    void manualTasksCompletion() throws Exception {
        given(service.listManualTasks(anyBoolean())).willReturn(List.of(Map.of("id", "task-1", "status", "PENDING")));
        given(service.completeManualTask(eq("task-1"), anyMap()))
                .willReturn(Map.of("id", "task-1", "status", "COMPLETED"));

        mvc.perform(get("/api/v2/manual-tasks")
                        .header(HttpHeaders.AUTHORIZATION, BEARER)
                        .header("X-Role", ROLE_ADMIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value("task-1"));

        mvc.perform(post("/api/v2/manual-tasks/task-1/complete")
                        .header(HttpHeaders.AUTHORIZATION, BEARER)
                        .header("X-Role", ROLE_ADMIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("confirmed", true))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("COMPLETED"));
    }

    @Test
    void templatesCatalogAndInstall() throws Exception {
        given(templates.list()).willReturn(List.of(Map.of("id", "high-risk-ioc", "name", "High-risk IOC triage")));
        given(templates.install("high-risk-ioc"))
                .willReturn(Map.of("templateId", "high-risk-ioc", "published", false));

        mvc.perform(get("/api/v2/templates")
                        .header(HttpHeaders.AUTHORIZATION, BEARER)
                        .header("X-Role", ROLE_ADMIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value("high-risk-ioc"));

        mvc.perform(post("/api/v2/templates/high-risk-ioc/install")
                        .header(HttpHeaders.AUTHORIZATION, BEARER)
                        .header("X-Role", ROLE_ADMIN))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.templateId").value("high-risk-ioc"));
    }

    @Test
    void automationRulesEndpoints() throws Exception {
        given(automationRules.list(any())).willReturn(new PageImpl<>(List.of(Map.of("id", "rule-1", "name", "Alert Rule")), PageRequest.of(0, 20), 1));
        given(automationRules.explain(anyMap())).willReturn(List.of(Map.of("ruleId", "rule-1", "matched", true)));
        given(automationRules.setEnabled("rule-1", true)).willReturn(Map.of("id", "rule-1", "enabled", true));

        mvc.perform(get("/api/v2/automation-rules?page=0&size=20")
                        .header(HttpHeaders.AUTHORIZATION, BEARER)
                        .header("X-Role", ROLE_ADMIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].id").value("rule-1"));

        mvc.perform(post("/api/v2/automation-rules/test")
                        .header(HttpHeaders.AUTHORIZATION, BEARER)
                        .header("X-Role", ROLE_ADMIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("eventId", "e-1", "type", "alert.created"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].matched").value(true));

        mvc.perform(post("/api/v2/automation-rules/rule-1/enable")
                        .header(HttpHeaders.AUTHORIZATION, BEARER)
                        .header("X-Role", ROLE_ADMIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.enabled").value(true));
    }

    @Test
    void connectorsAndOperations() throws Exception {
        given(connectors.list()).willReturn(List.of(Map.of("id", "socp.alert", "name", "SOCP Alert")));
        given(connectors.actions()).willReturn(List.of(Map.of("actionRef", "socp.alert/get@1")));
        given(connectors.test("conn-1")).willReturn(Map.of("status", "HEALTHY"));
        given(service.deadDispatches()).willReturn(List.of(Map.of("id", "dead-1", "runId", "run-999")));
        given(service.requeueDead(eq("dead-1"), anyString())).willReturn(Map.of("id", "dead-1", "status", "PENDING"));
        given(service.stats()).willReturn(Map.of("dispatchBacklog", 0, "signalBacklog", 0));
        given(service.definitionSchema()).willReturn(json.createObjectNode().put("title", "soar.playbook/v2"));

        mvc.perform(get("/api/v2/connectors")
                        .header(HttpHeaders.AUTHORIZATION, BEARER)
                        .header("X-Role", ROLE_ADMIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value("socp.alert"));

        mvc.perform(get("/api/v2/actions")
                        .header(HttpHeaders.AUTHORIZATION, BEARER)
                        .header("X-Role", ROLE_ADMIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].actionRef").value("socp.alert/get@1"));

        mvc.perform(post("/api/v2/connections/conn-1/test")
                        .header(HttpHeaders.AUTHORIZATION, BEARER)
                        .header("X-Role", ROLE_ADMIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("HEALTHY"));

        mvc.perform(get("/api/v2/operations/dead-dispatches")
                        .header(HttpHeaders.AUTHORIZATION, BEARER)
                        .header("X-Role", ROLE_ADMIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value("dead-1"));

        mvc.perform(post("/api/v2/operations/dead-dispatches/dead-1/requeue")
                        .header(HttpHeaders.AUTHORIZATION, BEARER)
                        .header("X-Role", ROLE_ADMIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("reason", "retry after db restart"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PENDING"));

        mvc.perform(get("/api/v2/stats")
                        .header(HttpHeaders.AUTHORIZATION, BEARER)
                        .header("X-Role", ROLE_ADMIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.dispatchBacklog").value(0));

        mvc.perform(get("/api/v2/definition-schema")
                        .header(HttpHeaders.AUTHORIZATION, BEARER)
                        .header("X-Role", ROLE_ADMIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.title").value("soar.playbook/v2"));
    }
}
