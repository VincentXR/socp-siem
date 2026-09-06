package com.socp.soar.web.api.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.socp.platform.error.api.ApiResult;
import com.socp.platform.tenant.context.TenantContext;
import com.socp.soar.web.api.request.CreateV2AutomationRuleRequest;
import com.socp.soar.web.api.request.CreateV2ConnectorRequest;
import com.socp.soar.web.api.request.CreateV2PlaybookRequest;
import com.socp.soar.web.api.request.ImportV2PlaybookRequest;
import com.socp.soar.web.api.request.RunV2Request;
import com.socp.soar.web.api.request.SaveV2VersionRequest;
import com.socp.soar.web.domain.v2.DefinitionValidationResult;
import com.socp.soar.web.service.SoarV2AutomationRuleService;
import com.socp.soar.web.service.SoarV2ConnectorService;
import com.socp.soar.web.service.SoarV2Service;
import com.socp.soar.web.service.SoarV2TemplateService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;

/**
 * Direct-instantiation coverage for the SOAR 2.0 REST surface: asserts the
 * ApiResult envelope, HTTP status selection and argument passing without a
 * servlet stack.
 */
@ExtendWith(MockitoExtension.class)
class SoarV2ControllerCoverageTest {

    @Mock
    private SoarV2Service service;
    @Mock
    private SoarV2AutomationRuleService automationRules;
    @Mock
    private SoarV2ConnectorService connectors;
    @Mock
    private SoarV2TemplateService templates;

    private final ObjectMapper mapper = new ObjectMapper();

    private SoarV2Controller controller;

    @BeforeEach
    void setUp() {
        TenantContext.set("tenant-a");
        controller = new SoarV2Controller(service, automationRules, connectors, templates);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    // ------------------------------------------------------------------
    // playbooks
    // ------------------------------------------------------------------

    @Test
    void listPlaybooksPassesFiltersAndClampsThePageSize() {
        given(service.listPlaybooks(any(Pageable.class), eq("ACTIVE"), eq("alice"), eq("net"), eq("high")))
                .willReturn(new PageImpl<>(List.of(Map.of("id", "pb-1")), PageRequest.of(0, 1), 3));

        ApiResult<Map<String, Object>> result = controller.listPlaybooks(0, 0, "ACTIVE", "alice", "net", "high");

        assertThat(result.code()).isZero();
        assertThat(result.data())
                .containsEntry("page", 0)
                .containsEntry("size", 1)
                .containsEntry("total", 3L);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) result.data().get("items");
        assertThat(items).hasSize(1);
        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(service).listPlaybooks(captor.capture(), eq("ACTIVE"), eq("alice"), eq("net"), eq("high"));
        assertThat(captor.getValue().getPageSize()).isEqualTo(1);
    }

    @Test
    void listPlaybooksClampsAnOversizedPage() {
        given(service.listPlaybooks(any(Pageable.class), eq("ACTIVE"), isNull(), isNull(), isNull()))
                .willReturn(Page.empty());

        controller.listPlaybooks(3, 500, "ACTIVE", null, null, null);

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(service).listPlaybooks(captor.capture(), eq("ACTIVE"), isNull(), isNull(), isNull());
        assertThat(captor.getValue().getPageNumber()).isEqualTo(3);
        assertThat(captor.getValue().getPageSize()).isEqualTo(200);
    }

    @Test
    void createPlaybookReturns201AndPassesMetadata() {
        given(service.createPlaybook("New", "desc", List.of("net")))
                .willReturn(Map.of("id", "pb-1", "name", "New"));

        ResponseEntity<ApiResult<Map<String, Object>>> response =
                controller.createPlaybook(new CreateV2PlaybookRequest("New", "desc", List.of("net")));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody().data()).containsEntry("id", "pb-1");
    }

    @Test
    void importPlaybookReturns201AndDelegatesToTheService() {
        JsonNode definition = mapper.valueToTree(Map.of("schemaVersion", "soar.playbook/v2"));
        given(service.importDraft("Imported", "d", List.of("t"), definition, null))
                .willReturn(Map.of("playbookId", "pb-9", "imported", true));

        ResponseEntity<ApiResult<Map<String, Object>>> response = controller.importPlaybook(
                new ImportV2PlaybookRequest("Imported", "d", List.of("t"), definition, null));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody().data()).containsEntry("imported", true);
    }

    @Test
    void updatePlaybookParsesOptionalFieldsAndRowVersion() {
        given(service.updatePlaybook("pb-1", "Renamed", "d", List.of("x"), "ACTIVE", 3L))
                .willReturn(Map.of("id", "pb-1", "name", "Renamed"));

        ApiResult<Map<String, Object>> result = controller.updatePlaybook("pb-1",
                Map.of("name", "Renamed", "description", "d", "status", "ACTIVE",
                        "rowVersion", 3, "tags", List.of("x")));

        assertThat(result.data()).containsEntry("name", "Renamed");
        verify(service).updatePlaybook("pb-1", "Renamed", "d", List.of("x"), "ACTIVE", 3L);
    }

    @Test
    void versionLifecycleEndpointsPassIdentifiersThrough() {
        given(service.getPlaybook("pb-1")).willReturn(Map.of("id", "pb-1"));
        given(service.listVersions("pb-1")).willReturn(List.of(Map.of("version", 1)));
        given(service.createVersion("pb-1")).willReturn(Map.of("version", 2, "status", "DRAFT"));
        given(service.getVersion("pb-1", 1)).willReturn(Map.of("version", 1));
        given(service.exportVersion("pb-1", 1)).willReturn(Map.of("version", 1, "exported", true));
        given(service.publish("pb-1", 1)).willReturn(Map.of("status", "PUBLISHED"));
        given(service.deprecate("pb-1", 1)).willReturn(Map.of("status", "DEPRECATED"));

        assertThat(controller.getPlaybook("pb-1").data()).containsEntry("id", "pb-1");
        assertThat(controller.versions("pb-1").data()).hasSize(1);
        assertThat(controller.createVersion("pb-1").getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(controller.createDraft("pb-1").getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(controller.version("pb-1", 1).data()).containsEntry("version", 1);
        assertThat(controller.exportVersion("pb-1", 1).data()).containsEntry("exported", true);
        assertThat(controller.publish("pb-1", 1).data()).containsEntry("status", "PUBLISHED");
        assertThat(controller.deprecate("pb-1", 1).data()).containsEntry("status", "DEPRECATED");
    }

    @Test
    void saveDraftSerializesDefinitionAndLayoutNodes() {
        JsonNode definition = mapper.valueToTree(Map.of("nodes", List.of()));
        JsonNode layout = mapper.valueToTree(Map.of("x", 1));
        given(service.saveDraft("pb-1", 1, definition.toString(), layout.toString(), 5L))
                .willReturn(Map.of("version", 1));

        ApiResult<Map<String, Object>> result =
                controller.saveDraft("pb-1", 1, new SaveV2VersionRequest(definition, layout, 5L));

        assertThat(result.data()).containsEntry("version", 1);
        verify(service).saveDraft("pb-1", 1, definition.toString(), layout.toString(), 5L);
    }

    @Test
    void validateAndSchemaAndDryRunDelegate() {
        DefinitionValidationResult validation =
                new DefinitionValidationResult(true, List.of(), List.of(), "soar.playbook/v2", "hash", 2, 2, 0);
        given(service.validateVersion("pb-1", 1)).willReturn(validation);
        JsonNode schema = mapper.createObjectNode().put("title", "soar.playbook/v2");
        given(service.definitionSchema()).willReturn(schema);
        Map<String, Object> subject = Map.of("id", "al-1");
        Map<String, Object> inputs = Map.of("k", "v");
        given(service.dryRun("pb-1", 2, subject, inputs)).willReturn(Map.of("status", "OK"));

        assertThat(controller.validate("pb-1", 1).data().valid()).isTrue();
        assertThat(controller.definitionSchema().data()).isEqualTo(schema);
        assertThat(controller.dryRun("pb-1", 2, Map.of("subject", subject, "inputs", inputs)).data())
                .containsEntry("status", "OK");
        verify(service).dryRun("pb-1", 2, subject, inputs);
    }

    @Test
    void dryRunWithoutPayloadFallsBackToEmptySubjectAndPayloadInputs() {
        given(service.dryRun(eq("pb-1"), eq(2), anyMap(), anyMap())).willReturn(Map.of("status", "OK"));

        controller.dryRun("pb-1", 2, null);

        verify(service).dryRun("pb-1", 2, Map.of(), Map.of());
    }

    // ------------------------------------------------------------------
    // runs
    // ------------------------------------------------------------------

    @Test
    void queueRunReturns202And200ForDuplicates() {
        given(service.queueManualRun("req-1", "ver-1", Map.of("id", "al-1"), Map.of("k", "v")))
                .willReturn(Map.of("runId", "run-1", "duplicate", false))
                .willReturn(Map.of("runId", "run-1", "duplicate", true));

        RunV2Request request = new RunV2Request("req-1", "ver-1", Map.of("id", "al-1"), Map.of("k", "v"));

        ResponseEntity<ApiResult<Map<String, Object>>> first = controller.queueRun(request);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        ResponseEntity<ApiResult<Map<String, Object>>> second = controller.queueRun(request);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void listRunsParsesInstantFiltersAndRejectsMalformedOnes() {
        given(service.listRuns(any(Pageable.class), eq("RUNNING"), eq("ver-1"), eq("manual"), eq("alice"),
                eq(Instant.parse("2026-01-01T00:00:00Z")), isNull()))
                .willReturn(Page.empty());

        ApiResult<Map<String, Object>> result = controller.listRuns(0, 20, "RUNNING", "ver-1", "manual",
                "alice", "2026-01-01T00:00:00Z", null);

        assertThat(result.code()).isZero();
        assertThatThrownBy(() -> controller.listRuns(0, 20, null, null, null, null, "not-a-date", null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("createdFrom");
    }

    @Test
    void runDetailAndLifecycleDefaultsReasonsWhenBodyIsMissing() {
        given(service.getRun("run-1")).willReturn(Map.of("runId", "run-1"));
        given(service.cancelRun("run-1", "operator requested cancellation"))
                .willReturn(Map.of("status", "CANCELLING"));
        given(service.retryRun("run-1", "operator requested retry"))
                .willReturn(Map.of("runId", "run-2", "status", "QUEUED"));
        given(service.rerun("run-1", "operator requested rerun", true))
                .willReturn(Map.of("runId", "run-3", "status", "QUEUED"));

        assertThat(controller.getRun("run-1").data()).containsEntry("runId", "run-1");
        assertThat(controller.cancel("run-1", null).data()).containsEntry("status", "CANCELLING");
        assertThat(controller.retry("run-1", null).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(controller.rerun("run-1", Map.of("confirm", true)).getStatusCode())
                .isEqualTo(HttpStatus.ACCEPTED);
        verify(service).rerun("run-1", "operator requested rerun", true);
    }

    @Test
    void nodeArtifactsAndAttemptsEndpointsSupportPaging() {
        given(service.listNodes("run-1", PageRequest.of(0, 200)))
                .willReturn(new PageImpl<>(List.of(Map.of("nodeId", "n-1")), PageRequest.of(0, 200), 1));
        given(service.listNodes("run-1")).willReturn(List.of(Map.of("nodeId", "n-1")));
        given(service.listArtifacts("run-1")).willReturn(List.of(Map.of("id", "art-1")));
        given(service.listNodeAttempts("node-1", PageRequest.of(1, 10)))
                .willReturn(new PageImpl<>(List.of(Map.of("attemptNo", 2)), PageRequest.of(1, 10), 1));

        ApiResult<Object> pagedNodes = controller.nodes("run-1", 0, 500);
        assertThat(pagedNodes.data()).isInstanceOf(Map.class);
        ApiResult<Object> plainNodes = controller.nodes("run-1", null, null);
        assertThat(plainNodes.data()).isInstanceOf(List.class);
        assertThat(controller.artifacts("run-1", null, null).data()).isInstanceOf(List.class);
        ApiResult<Map<String, Object>> attempts = controller.attempts("node-1", 1, 10);
        assertThat(attempts.data()).isInstanceOf(Map.class);
        verify(service).listNodes("run-1", PageRequest.of(0, 200));
        verify(service).listNodeAttempts("node-1", PageRequest.of(1, 10));
    }

    @Test
    void artifactEndpointsPassThroughContentAndMetadata() {
        JsonNode body = mapper.valueToTree(Map.of("size", 3));
        given(service.uploadArtifact("run-1", "node-1", "text/csv", "RESTRICTED", body))
                .willReturn(Map.of("id", "art-1"));
        given(service.getArtifact("art-1")).willReturn(Map.of("id", "art-1", "sizeBytes", 3));
        given(service.getArtifactContent("art-1")).willReturn("{\"a\":1}");

        ResponseEntity<ApiResult<Map<String, Object>>> uploaded =
                controller.uploadArtifact("run-1", "node-1", "text/csv", "RESTRICTED", body);
        assertThat(uploaded.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(uploaded.getBody().data()).containsEntry("id", "art-1");

        assertThat(controller.artifact("art-1").data()).containsEntry("sizeBytes", 3);
        ResponseEntity<String> content = controller.artifactContent("art-1");
        assertThat(content.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(content.getBody()).isEqualTo("{\"a\":1}");
    }

    @Test
    void eventsEndpointUsesTheResumeCursorWhenProvided() {
        given(service.listEvents("run-1", 5L, PageRequest.of(0, 100)))
                .willReturn(new PageImpl<>(List.of(Map.of("sequence", 7L)), PageRequest.of(0, 100), 1));
        given(service.listEvents("run-1")).willReturn(List.of(Map.of("sequence", 1L)));

        ApiResult<Object> paged = controller.events("run-1", null, null, 5L);
        assertThat(paged.data()).isInstanceOf(Map.class);
        ApiResult<Object> plain = controller.events("run-1", null, null, 0L);
        assertThat(plain.data()).isInstanceOf(List.class);
        verify(service).listEvents("run-1", 5L, PageRequest.of(0, 100));
    }

    @Test
    void streamCapturesTheTenantAndReturnsAnEmitter() {
        lenient().when(service.listEvents(eq("run-1"), anyLong(), any(Pageable.class))).thenReturn(Page.empty());

        SseEmitter emitter = controller.stream("run-1", "3");

        assertThat(emitter).isNotNull();
    }

    // ------------------------------------------------------------------
    // operations, manual tasks and stats
    // ------------------------------------------------------------------

    @Test
    void resolveUnknownPassesResolutionEvidenceAndReason() {
        given(service.resolveUnknown("node-1", "CONFIRMED_SUCCEEDED", "evidence", "why"))
                .willReturn(Map.of("nodeRunId", "node-1"));

        ApiResult<Map<String, Object>> result = controller.resolveUnknown("node-1",
                Map.of("resolution", "CONFIRMED_SUCCEEDED", "evidence", "evidence", "reason", "why"));

        assertThat(result.data()).containsEntry("nodeRunId", "node-1");
    }

    @Test
    void manualTasksSupportPagingAndCompletion() {
        given(service.listManualTasks(true)).willReturn(List.of(Map.of("id", "task-1")));
        given(service.listManualTasks(false, PageRequest.of(0, 200)))
                .willReturn(new PageImpl<>(List.of(Map.of("id", "task-2")), PageRequest.of(0, 200), 1));
        given(service.completeManualTask("task-1", Map.of("confirmed", true)))
                .willReturn(Map.of("id", "task-1", "status", "COMPLETED"));

        assertThat(controller.manualTasks(true, null, null).data()).isInstanceOf(List.class);
        assertThat(controller.manualTasks(false, null, 300).data()).isInstanceOf(Map.class);
        assertThat(controller.completeManualTask("task-1", Map.of("confirmed", true)).data())
                .containsEntry("status", "COMPLETED");
        verify(service).listManualTasks(false, PageRequest.of(0, 200));
    }

    @Test
    void statsAndDeadDispatchOperationsDelegate() {
        given(service.stats()).willReturn(Map.of("dispatchBacklog", 0));
        given(service.deadDispatches()).willReturn(List.of(Map.of("id", "dead-1")));
        given(service.requeueDead("dead-1", "operator requeue")).willReturn(Map.of("id", "dead-1"));
        given(service.discardDead("dead-1", "junk")).willReturn(Map.of("id", "dead-1", "status", "DISCARDED"));

        assertThat(controller.stats().data()).containsEntry("dispatchBacklog", 0);
        assertThat(controller.deadDispatches().data()).hasSize(1);
        assertThat(controller.requeueDead("dead-1", null).data()).containsEntry("id", "dead-1");
        assertThat(controller.discardDead("dead-1", Map.of("reason", "junk")).data())
                .containsEntry("status", "DISCARDED");
    }

    // ------------------------------------------------------------------
    // approvals
    // ------------------------------------------------------------------

    @Test
    void approvalsListSupportsPagingAndDecisionsValidateTheVerb() {
        given(service.listApprovals(PageRequest.of(0, 200)))
                .willReturn(new PageImpl<>(List.of(Map.of("id", "appr-1")), PageRequest.of(0, 200), 1));
        given(service.listApprovals()).willReturn(List.of(Map.of("id", "appr-1")));
        given(service.decideApproval("appr-1", true, "authorized")).willReturn(Map.of("status", "APPROVED"));

        assertThat(controller.approvals(null, 300).data()).isInstanceOf(Map.class);
        assertThat(controller.approvals(null, null).data()).isInstanceOf(List.class);

        assertThatThrownBy(() -> controller.decideApproval("appr-1", Map.of("decision", "MAYBE")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("APPROVE or REJECT");
        assertThat(controller.decideApproval("appr-1", Map.of("decision", "approve", "reason", "authorized")).data())
                .containsEntry("status", "APPROVED");
    }

    @Test
    void approveAndRejectShortcutsPassTheDecisionFlag() {
        given(service.decideApproval("appr-1", true, "ok")).willReturn(Map.of("status", "APPROVED"));
        given(service.decideApproval("appr-1", false, null)).willReturn(Map.of("status", "REJECTED"));

        assertThat(controller.approve("appr-1", Map.of("reason", "ok")).data()).containsEntry("status", "APPROVED");
        assertThat(controller.reject("appr-1", null).data()).containsEntry("status", "REJECTED");
    }

    // ------------------------------------------------------------------
    // automation rules
    // ------------------------------------------------------------------

    @Test
    void automationRuleLifecycleEndpointsPassArgumentsThrough() {
        JsonNode conditions = mapper.valueToTree(Map.of("severity", "HIGH"));
        JsonNode actions = mapper.valueToTree(List.of(Map.of("actionRef", "socp.alert/get")));
        CreateV2AutomationRuleRequest request =
                new CreateV2AutomationRuleRequest("rule", "alert.created", 5, true, conditions, actions, null, 4L);
        given(automationRules.create("rule", "alert.created", 5, true, conditions, actions, null))
                .willReturn(Map.of("id", "rule-1"));
        given(automationRules.update("rule-1", "rule", "alert.created", 5, true, conditions, actions, null, 4L))
                .willReturn(Map.of("id", "rule-1"));
        given(automationRules.get("rule-1")).willReturn(Map.of("id", "rule-1"));
        given(automationRules.patch("rule-1", Map.of("priority", 9))).willReturn(Map.of("priority", 9));
        given(automationRules.remove("rule-1")).willReturn(Map.of("removed", true));
        given(automationRules.setEnabled("rule-1", true)).willReturn(Map.of("enabled", true));
        given(automationRules.setEnabled("rule-1", false)).willReturn(Map.of("enabled", false));
        given(automationRules.list(PageRequest.of(0, 200)))
                .willReturn(new PageImpl<>(List.of(Map.of("id", "rule-1")), PageRequest.of(0, 200), 1));
        given(automationRules.list()).willReturn(List.of(Map.of("id", "rule-1")));
        Map<String, Object> event = Map.of("type", "alert.created");
        given(automationRules.evaluate(event)).willReturn(Map.of("matched", true));
        given(automationRules.explain(event)).willReturn(List.of(Map.of("ruleId", "rule-1")));

        assertThat(controller.createAutomationRule(request).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(controller.updateAutomationRule("rule-1", request).data()).containsEntry("id", "rule-1");
        assertThat(controller.patchAutomationRule("rule-1", Map.of("priority", 9)).data()).containsEntry("priority", 9);
        assertThat(controller.deleteAutomationRule("rule-1").data()).containsEntry("removed", true);
        assertThat(controller.enableAutomationRule("rule-1").data()).containsEntry("enabled", true);
        assertThat(controller.disableAutomationRule("rule-1").data()).containsEntry("enabled", false);
        assertThat(controller.automationRules(null, 300).data()).isInstanceOf(Map.class);
        assertThat(controller.automationRules(null, null).data()).isInstanceOf(List.class);
        assertThat(controller.getAutomationRule("rule-1").data()).containsEntry("id", "rule-1");
        assertThat(controller.evaluateAutomationRules(event).data()).containsEntry("matched", true);
        assertThat(controller.testAutomationRules(event).data()).hasSize(1);
        assertThat(controller.evaluateEvent(event).data()).containsEntry("matched", true);
    }

    // ------------------------------------------------------------------
    // connectors / connections
    // ------------------------------------------------------------------

    @Test
    void connectorEndpointsPassArgumentsThrough() {
        given(connectors.list()).willReturn(List.of(Map.of("id", "conn-1")));
        given(connectors.list(PageRequest.of(0, 200)))
                .willReturn(new PageImpl<>(List.of(Map.of("id", "conn-1")), PageRequest.of(0, 200), 1));
        given(connectors.get("conn-1")).willReturn(Map.of("id", "conn-1"));
        given(connectors.actions()).willReturn(List.of(Map.of("actionRef", "socp.alert/get@1")));
        given(connectors.test("conn-1")).willReturn(Map.of("status", "HEALTHY"));
        given(connectors.setEnabled("conn-1", true)).willReturn(Map.of("enabled", true));
        given(connectors.setEnabled("conn-1", false)).willReturn(Map.of("enabled", false));
        given(connectors.softDelete("conn-1")).willReturn(Map.of("deleted", true));

        assertThat(controller.connectors().data()).hasSize(1);
        assertThat(controller.connections(null, 300).data()).isInstanceOf(Map.class);
        assertThat(controller.getConnector("conn-1").data()).containsEntry("id", "conn-1");
        assertThat(controller.getConnection("conn-1").data()).containsEntry("id", "conn-1");
        assertThat(controller.actions().data()).hasSize(1);
        assertThat(controller.testConnector("conn-1").data()).containsEntry("status", "HEALTHY");
        assertThat(controller.testConnection("conn-1").data()).containsEntry("status", "HEALTHY");
        assertThat(controller.enableConnector("conn-1").data()).containsEntry("enabled", true);
        assertThat(controller.disableConnector("conn-1").data()).containsEntry("enabled", false);
        assertThat(controller.enableConnection("conn-1").data()).containsEntry("enabled", true);
        assertThat(controller.disableConnection("conn-1").data()).containsEntry("enabled", false);
        assertThat(controller.deleteConnection("conn-1").data()).containsEntry("deleted", true);
        verify(connectors).list(PageRequest.of(0, 200));
    }

    @Test
    void createAndUpdateConnectionReturnCreatedAndPassMetadata() {
        CreateV2ConnectorRequest request = new CreateV2ConnectorRequest("fw", "endpoint",
                "https://fw.example", "secret://fw", List.of("fw.example"), true, 7L);
        given(connectors.create("fw", "endpoint", "https://fw.example", "secret://fw",
                List.of("fw.example"), true)).willReturn(Map.of("id", "conn-1"));
        given(connectors.update("conn-1", "fw", "endpoint", "https://fw.example", "secret://fw",
                List.of("fw.example"), true, 7L)).willReturn(Map.of("id", "conn-1"));

        assertThat(controller.createConnection(request).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(controller.updateConnection("conn-1", request).data()).containsEntry("id", "conn-1");
        assertThat(controller.createConnector(request).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(controller.updateConnector("conn-1", request).data()).containsEntry("id", "conn-1");
    }

    @Test
    void patchConnectionMergesThePayloadOverTheCurrentConnection() {
        Map<String, Object> current = Map.of("name", "old", "connectorType", "endpoint",
                "endpoint", "https://old", "allowedHosts", List.of("a.example"), "enabled", true);
        given(connectors.get("conn-1")).willReturn(current);
        given(connectors.update("conn-1", "new", "endpoint", "https://old", null,
                List.of("a.example"), false, 4L)).willReturn(Map.of("id", "conn-1"));

        ApiResult<Map<String, Object>> result = controller.patchConnection("conn-1",
                Map.of("name", "new", "enabled", "false", "rowVersion", 4));

        assertThat(result.data()).containsEntry("id", "conn-1");
        verify(connectors).update("conn-1", "new", "endpoint", "https://old", null,
                List.of("a.example"), false, 4L);
    }

    @Test
    void patchConnectionUsesAnExplicitAllowedHostsList() {
        given(connectors.get("conn-1")).willReturn(Map.of("name", "old"));
        given(connectors.update(eq("conn-1"), eq("old"), isNull(), isNull(), isNull(),
                eq(List.of("b.example")), eq(true), isNull())).willReturn(Map.of("id", "conn-1"));

        controller.patchConnection("conn-1",
                Map.of("allowedHosts", List.of("b.example"), "enabled", true));

        verify(connectors).update(eq("conn-1"), eq("old"), isNull(), isNull(), isNull(),
                eq(List.of("b.example")), eq(true), isNull());
    }

    // ------------------------------------------------------------------
    // templates
    // ------------------------------------------------------------------

    @Test
    void templatesListAndInstallDelegate() {
        given(templates.list()).willReturn(List.of(Map.of("id", "tpl-1")));
        given(templates.install("tpl-1")).willReturn(Map.of("templateId", "tpl-1"));

        assertThat(controller.templates().data()).hasSize(1);
        ResponseEntity<ApiResult<Map<String, Object>>> installed = controller.installTemplate("tpl-1");
        assertThat(installed.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(installed.getBody().data()).containsEntry("templateId", "tpl-1");
    }

    @Test
    void templateRoutesFailFastWithoutATemplateCatalog() {
        SoarV2Controller withoutTemplates =
                new SoarV2Controller(service, automationRules, connectors);

        assertThat(withoutTemplates.templates().data()).isEqualTo(List.of());
        assertThatThrownBy(() -> withoutTemplates.installTemplate("tpl-1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("template catalog unavailable");
    }
}
