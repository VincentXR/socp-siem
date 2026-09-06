package com.socp.soar.web.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.socp.platform.tenant.context.TenantContext;
import com.socp.soar.web.config.SoarRuntimeProperties;
import com.socp.soar.web.connector.ActionDescriptor;
import com.socp.soar.web.connector.ConnectorDescriptor;
import com.socp.soar.web.connector.SoarConnectorRegistry;
import com.socp.soar.web.definition.SoarDefinitionValidator;
import com.socp.soar.web.domain.v2.DefinitionValidationResult;
import com.socp.soar.web.domain.v2.SoarRunStatus;
import com.socp.soar.web.persistence.entity.PlaybookVersionEntity;
import com.socp.soar.web.persistence.entity.SoarApprovalDecisionEntity;
import com.socp.soar.web.persistence.entity.SoarApprovalEntity;
import com.socp.soar.web.persistence.entity.SoarConnectorEntity;
import com.socp.soar.web.persistence.entity.SoarDispatchOutboxEntity;
import com.socp.soar.web.persistence.entity.SoarManualTaskEntity;
import com.socp.soar.web.persistence.entity.SoarPlaybookEntity;
import com.socp.soar.web.persistence.entity.SoarRunEntity;
import com.socp.soar.web.persistence.entity.SoarRunEventEntity;
import com.socp.soar.web.persistence.entity.SoarSignalOutboxEntity;
import com.socp.soar.web.persistence.repository.PlaybookVersionRepository;
import com.socp.soar.web.persistence.repository.SoarActionAttemptRepository;
import com.socp.soar.web.persistence.repository.SoarApprovalDecisionRepository;
import com.socp.soar.web.persistence.repository.SoarApprovalRepository;
import com.socp.soar.web.persistence.repository.SoarArtifactRepository;
import com.socp.soar.web.persistence.repository.SoarConnectorRepository;
import com.socp.soar.web.persistence.repository.SoarDispatchOutboxRepository;
import com.socp.soar.web.persistence.repository.SoarManualTaskRepository;
import com.socp.soar.web.persistence.repository.SoarNodeRunRepository;
import com.socp.soar.web.persistence.repository.SoarPlaybookRepository;
import com.socp.soar.web.persistence.repository.SoarRunEventRepository;
import com.socp.soar.web.persistence.repository.SoarRunRepository;
import com.socp.soar.web.persistence.repository.SoarSignalOutboxRepository;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Edge-branch coverage for {@link SoarV2Service}: automation re-validation,
 * connector health checks, dry-run, approval gate decisions with the durable
 * vote projection, expiry janitor, manual-task form schema validation and the
 * blank-key signal fallback.
 *
 * <p>Everything is a Mockito mock (or a plain properties object); nothing
 * touches the network, a database or Temporal.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SoarV2ServiceEdgeBranchesCoverageTest {

    private static final String SAFE_DEFINITION = "{\"schemaVersion\":\"soar.playbook/v2\","
            + "\"entryNodeId\":\"start\",\"nodes\":["
            + "{\"id\":\"start\",\"type\":\"START\",\"name\":\"Start\"},"
            + "{\"id\":\"notify\",\"type\":\"ACTION\",\"actionRef\":\"socp.notify/send-channel\"},"
            + "{\"id\":\"end\",\"type\":\"END\",\"name\":\"End\",\"outcome\":\"SUCCEEDED\"}],"
            + "\"edges\":[{\"from\":\"start\",\"to\":\"end\"}]}";

    private static final String CONNECTED_DEFINITION = "{\"schemaVersion\":\"soar.playbook/v2\","
            + "\"entryNodeId\":\"start\",\"nodes\":["
            + "{\"id\":\"start\",\"type\":\"START\"},"
            + "{\"id\":\"act\",\"type\":\"ACTION\",\"actionRef\":\"endpoint/custom-action\","
            + "\"connectionRef\":\"conn-1\"},"
            + "{\"id\":\"end\",\"type\":\"END\",\"outcome\":\"SUCCEEDED\"}],"
            + "\"edges\":[{\"from\":\"start\",\"to\":\"act\"},{\"from\":\"act\",\"to\":\"end\"}]}";

    private static final String POLICY_DEFINITION = "{\"schemaVersion\":\"soar.playbook/v2\","
            + "\"entryNodeId\":\"start\",\"nodes\":["
            + "{\"id\":\"start\",\"type\":\"START\"},"
            + "{\"id\":\"contain\",\"type\":\"ACTION\",\"actionRef\":\"endpoint/custom-action\","
            + "\"connectionRef\":\"conn-1\",\"target\":{\"host\":\"web-1\"}},"
            + "{\"id\":\"end\",\"type\":\"END\",\"outcome\":\"SUCCEEDED\"}],"
            + "\"edges\":[{\"from\":\"start\",\"to\":\"end\"}],"
            + "\"approvalPolicy\":{\"allowedRoles\":[\" SOAR-ADMIN \"],"
            + "\"approverGroups\":[\"group-1\"],\"approvalsRequired\":3}}";

    @Mock
    private SoarPlaybookRepository playbooks;
    @Mock
    private PlaybookVersionRepository versions;
    @Mock
    private SoarRunRepository runs;
    @Mock
    private SoarDispatchOutboxRepository dispatches;
    @Mock
    private SoarNodeRunRepository nodes;
    @Mock
    private SoarRunEventRepository events;
    @Mock
    private SoarApprovalRepository approvals;
    @Mock
    private SoarDefinitionValidator validator;
    @Mock
    private TemporalExecutor temporal;
    @Mock
    private SoarActionAttemptRepository attempts;
    @Mock
    private SoarManualTaskRepository manualTasks;
    @Mock
    private SoarSignalOutboxRepository signals;
    @Mock
    private SoarArtifactRepository artifacts;
    @Mock
    private SoarConnectorRepository connectors;
    @Mock
    private SoarConnectorRegistry connectorRegistry;
    @Mock
    private SoarApprovalDecisionRepository decisions;

    private final ObjectMapper mapper = new ObjectMapper();
    private SoarRuntimeProperties runtimeProperties;
    private SoarV2Service service;

    @BeforeEach
    void setUp() {
        TenantContext.set("tenant-a");
        runtimeProperties = new SoarRuntimeProperties();
        service = new SoarV2Service(playbooks, versions, runs, dispatches, nodes, events, approvals,
                validator, mapper, temporal, attempts, manualTasks, signals, connectors, connectorRegistry);
        service.setArtifacts(artifacts);
        service.setRuntimeProperties(runtimeProperties);
        service.setApprovalDecisions(decisions);
        // appendEvent locks the owning run before it allocates a sequence number.
        given(runs.findByTenantIdAndIdForUpdate(eq("tenant-a"), anyString()))
                .willReturn(Optional.of(run("run-lock", "req-lock")));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    // ------------------------------------------------------------------ versions

    @Test
    void listVersionsReturnsTenantScopedHistoryNewestFirst() {
        given(playbooks.findByTenantIdAndId("tenant-a", "pb-1"))
                .willReturn(Optional.of(playbook("pb-1", "ACTIVE")));
        given(versions.findByTenantIdAndPlaybookIdOrderByVersionNoDesc("tenant-a", "pb-1"))
                .willReturn(List.of(version("ver-2", "pb-1", "PUBLISHED", SAFE_DEFINITION),
                        version("ver-3", "pb-1", "DRAFT", SAFE_DEFINITION)));

        List<Map<String, Object>> result = service.listVersions("pb-1");

        assertThat(result).hasSize(2);
        assertThat(result).extracting(view -> view.get("status"))
                .containsExactly("PUBLISHED", "DRAFT");
        assertThat(result.get(0)).containsEntry("playbookId", "pb-1");
    }

    // ------------------------------------------- validatePublishedVersionForAutomation

    @Test
    void automationValidationRejectsMissingVersionAndDraftVersion() {
        given(versions.findByTenantIdAndId("tenant-a", "ver-missing")).willReturn(Optional.empty());
        assertRejected(HttpStatus.NOT_FOUND, "SOAR_VERSION_NOT_FOUND",
                () -> service.validatePublishedVersionForAutomation("ver-missing"));

        given(versions.findByTenantIdAndId("tenant-a", "ver-draft"))
                .willReturn(Optional.of(version("ver-draft", "pb-1", "DRAFT", SAFE_DEFINITION)));
        assertRejected(HttpStatus.CONFLICT, "SOAR_VERSION_NOT_PUBLISHED",
                () -> service.validatePublishedVersionForAutomation("ver-draft"));
    }

    @Test
    void automationValidationRejectsMissingAndArchivedPlaybook() {
        PlaybookVersionEntity published = version("ver-1", "pb-missing", "PUBLISHED", SAFE_DEFINITION);
        given(versions.findByTenantIdAndId("tenant-a", "ver-1")).willReturn(Optional.of(published));
        given(playbooks.findByTenantIdAndId("tenant-a", "pb-missing")).willReturn(Optional.empty());
        assertRejected(HttpStatus.NOT_FOUND, "SOAR_PLAYBOOK_NOT_FOUND",
                () -> service.validatePublishedVersionForAutomation("ver-1"));

        PlaybookVersionEntity archived = version("ver-2", "pb-archived", "PUBLISHED", SAFE_DEFINITION);
        given(versions.findByTenantIdAndId("tenant-a", "ver-2")).willReturn(Optional.of(archived));
        given(playbooks.findByTenantIdAndId("tenant-a", "pb-archived"))
                .willReturn(Optional.of(playbook("pb-archived", "ARCHIVED")));
        assertRejected(HttpStatus.CONFLICT, "SOAR_PLAYBOOK_ARCHIVED",
                () -> service.validatePublishedVersionForAutomation("ver-2"));
    }

    @Test
    void automationValidationRejectsDefinitionThatNoLongerValidates() {
        stubAutomationTarget(SAFE_DEFINITION, "PUBLISHED", "ACTIVE");
        given(validator.validate(SAFE_DEFINITION)).willReturn(validation(false, 0));

        assertRejected(HttpStatus.BAD_REQUEST, "SOAR_DEFINITION_INVALID",
                () -> service.validatePublishedVersionForAutomation("ver-1"));
    }

    @Test
    void automationValidationRechecksConnectorHealthAndBindings() {
        stubAutomationTarget(CONNECTED_DEFINITION, "PUBLISHED", "ACTIVE");
        given(validator.validate(CONNECTED_DEFINITION)).willReturn(validation(true, 0));
        stubRegistryAction("custom-action", "MEDIUM", true);
        given(connectors.findByTenantIdAndId("tenant-a", "conn-1"))
                .willReturn(Optional.of(connection("conn-1", "endpoint", true)));

        service.validatePublishedVersionForAutomation("ver-1");

        verify(connectors).findByTenantIdAndId("tenant-a", "conn-1");
    }

    @Test
    void connectionValidationRejectsUnknownActionAndBrokenConnections() {
        stubAutomationTarget(CONNECTED_DEFINITION, "PUBLISHED", "ACTIVE");
        given(validator.validate(CONNECTED_DEFINITION)).willReturn(validation(true, 0));
        // unknown action
        given(connectorRegistry.descriptorForAction("endpoint/custom-action")).willReturn(Optional.empty());
        assertRejected(HttpStatus.BAD_REQUEST, "SOAR_ACTION_NOT_FOUND",
                () -> service.validatePublishedVersionForAutomation("ver-1"));

        // known action, missing connection
        stubRegistryAction("custom-action", "MEDIUM", true);
        given(connectors.findByTenantIdAndId("tenant-a", "conn-1")).willReturn(Optional.empty());
        assertRejected(HttpStatus.BAD_REQUEST, "SOAR_CONNECTION_UNAVAILABLE",
                () -> service.validatePublishedVersionForAutomation("ver-1"));

        // disabled connection
        given(connectors.findByTenantIdAndId("tenant-a", "conn-1"))
                .willReturn(Optional.of(connection("conn-1", "endpoint", false)));
        assertRejected(HttpStatus.BAD_REQUEST, "SOAR_CONNECTION_UNAVAILABLE",
                () -> service.validatePublishedVersionForAutomation("ver-1"));

        // connector type does not match the action
        given(connectors.findByTenantIdAndId("tenant-a", "conn-1"))
                .willReturn(Optional.of(connection("conn-1", "firewall", true)));
        assertRejected(HttpStatus.BAD_REQUEST, "SOAR_CONNECTION_UNAVAILABLE",
                () -> service.validatePublishedVersionForAutomation("ver-1"));
    }

    @Test
    void productionMaturityRejectsConnectorsWithoutProductionAdapter() {
        runtimeProperties.setMaturity("production");
        stubAutomationTarget(CONNECTED_DEFINITION, "PUBLISHED", "ACTIVE");
        given(validator.validate(CONNECTED_DEFINITION)).willReturn(validation(true, 0));
        ConnectorDescriptor testOnly = new ConnectorDescriptor("endpoint", 1, "Endpoint Response",
                false, List.of(actionDescriptor("custom-action", true, "MEDIUM")));
        given(connectorRegistry.descriptorForAction("endpoint/custom-action"))
                .willReturn(Optional.of(testOnly));
        given(connectorRegistry.canonicalActionRef("endpoint/custom-action"))
                .willReturn("endpoint/custom-action");

        assertRejected(HttpStatus.CONFLICT, "SOAR_CONNECTOR_NOT_PRODUCTION_READY",
                () -> service.validatePublishedVersionForAutomation("ver-1"));
    }

    // ---------------------------------------------------------------------- dryRun

    @Test
    void dryRunReturnsSimulatedPreviewWithoutSideEffects() {
        stubVersionLookup(SAFE_DEFINITION);
        given(validator.validate(SAFE_DEFINITION)).willReturn(validation(true, 0));

        Map<String, Object> result = service.dryRun("pb-1", 3, Map.of("type", "alert"), Map.of());

        assertThat(result).containsEntry("status", "SIMULATED").containsEntry("mode", "DRY_RUN");
    }

    @Test
    void dryRunRejectsInvalidDraftAsBadRequest() {
        stubVersionLookup(SAFE_DEFINITION);
        given(validator.validate(SAFE_DEFINITION)).willReturn(validation(false, 0));

        assertRejected(HttpStatus.BAD_REQUEST, "SOAR_DRY_RUN_INVALID",
                () -> service.dryRun("pb-1", 3, null, null));
    }

    // ------------------------------------- high-risk admission with registry + policy

    @Test
    void highRiskRunCapturesApprovalPolicyAndRegistryRiskEvidence() {
        given(runs.findByTenantIdAndRequestId(eq("tenant-a"), anyString())).willReturn(Optional.empty());
        given(versions.findByTenantIdAndId("tenant-a", "ver-9"))
                .willReturn(Optional.of(version("ver-9", "pb-1", "PUBLISHED", POLICY_DEFINITION)));
        given(playbooks.findByTenantIdAndId("tenant-a", "pb-1"))
                .willReturn(Optional.of(playbook("pb-1", "ACTIVE")));
        given(validator.validate(POLICY_DEFINITION)).willReturn(validation(true, 1));
        stubRegistryAction("custom-action", "HIGH", true);
        given(connectors.findByTenantIdAndId("tenant-a", "conn-1"))
                .willReturn(Optional.of(connection("conn-1", "endpoint", true)));

        Map<String, Object> queued = service.queueManualRun("req-policy", "ver-9",
                Map.of("type", "host"), null);

        assertThat(queued).containsEntry("status", SoarRunStatus.WAITING_APPROVAL.name());
        ArgumentCaptor<SoarApprovalEntity> approvalCaptor = ArgumentCaptor.forClass(SoarApprovalEntity.class);
        verify(approvals).save(approvalCaptor.capture());
        SoarApprovalEntity approval = approvalCaptor.getValue();
        assertThat(approval.getActionRef()).isEqualTo("endpoint/custom-action");
        assertThat(approval.getTargetSnapshotJson()).contains("endpoint/custom-action")
                .contains("web-1").contains("conn-1")
                .contains("allowedRoles").contains("SOAR-ADMIN")
                .contains("allowedGroups").contains("approvalsRequired\":3");
        assertThat(approval.getPolicyJson()).contains("allowedRoles");
        verify(dispatches).save(any(SoarDispatchOutboxEntity.class));
    }

    // ------------------------------------------------------------------ decideApproval

    @Test
    void decideApprovalDeniesSelfApprovalThroughRecentEditorVote() {
        stubApprovalLookup(approval("apr-self", "run-lock", "PENDING"));
        PlaybookVersionEntity editedByApprover = version("ver-1", "pb-1", "PUBLISHED", SAFE_DEFINITION);
        editedByApprover.setCreatedBy("operator");
        given(versions.findByTenantIdAndId("tenant-a", "ver-1")).willReturn(Optional.of(editedByApprover));

        assertRejected(HttpStatus.FORBIDDEN, "SOAR_SELF_APPROVAL_DENIED",
                () -> service.decideApproval("apr-self", true, "looks good"));
        verify(approvals, never()).save(any(SoarApprovalEntity.class));
    }

    @Test
    void decideApprovalFailsClosedWhenPolicyAllowsListDoesNotMatch() {
        SoarApprovalEntity approval = approval("apr-policy", "run-lock", "PENDING");
        approval.setPolicyJson("{\"allowedRoles\":[\"SOAR_ADMIN\"]}");
        stubApprovalLookup(approval);

        assertRejected(HttpStatus.FORBIDDEN, "SOAR_APPROVER_POLICY_FORBIDDEN",
                () -> service.decideApproval("apr-policy", true, "reason"));
    }

    @Test
    void multiVotePolicyWithoutDecisionStoreFailsClosed() {
        SoarV2Service legacyWiring = new SoarV2Service(playbooks, versions, runs, dispatches, nodes,
                events, approvals, validator, mapper, temporal, attempts, manualTasks, signals,
                null, null);
        SoarApprovalEntity approval = approval("apr-multi", "run-lock", "PENDING");
        approval.setRequiredApprovals(2);
        stubApprovalLookup(approval);

        assertRejected(HttpStatus.SERVICE_UNAVAILABLE, "SOAR_APPROVAL_DECISION_STORE_UNAVAILABLE",
                () -> legacyWiring.decideApproval("apr-multi", true, "reason"));
    }

    @Test
    void repeatedVoteFromSameApproverIsIdempotent() {
        SoarApprovalEntity approval = approval("apr-idem", "run-lock", "PENDING");
        stubApprovalLookup(approval);
        SoarApprovalDecisionEntity prior = vote("v-1", "apr-idem", "APPROVE");
        given(decisions.findByTenantIdAndApprovalIdAndActorId("tenant-a", "apr-idem", "operator"))
                .willReturn(Optional.of(prior));
        given(decisions.findByTenantIdAndApprovalIdOrderByCreatedAtAsc("tenant-a", "apr-idem"))
                .willReturn(List.of(prior));

        Map<String, Object> view = service.decideApproval("apr-idem", true, "second submit");

        assertThat(view).containsEntry("id", "apr-idem").containsEntry("approvedVotes", 1L);
        verify(approvals, never()).save(any(SoarApprovalEntity.class));
    }

    @Test
    void intermediateVoteKeepsGatePendingUntilQuorum() {
        SoarApprovalEntity approval = approval("apr-vote", "run-lock", "PENDING");
        approval.setRequiredApprovals(2);
        stubApprovalLookup(approval);
        given(decisions.findByTenantIdAndApprovalIdOrderByCreatedAtAsc("tenant-a", "apr-vote"))
                .willReturn(List.of());

        Map<String, Object> view = service.decideApproval("apr-vote", true, "ship it");

        assertThat(view).containsEntry("status", "PENDING");
        assertThat(approval.getStatus()).isEqualTo("PENDING");
        assertThat(approval.getApprover()).isEqualTo("operator");
        ArgumentCaptor<SoarApprovalDecisionEntity> voteCaptor =
                ArgumentCaptor.forClass(SoarApprovalDecisionEntity.class);
        verify(decisions).save(voteCaptor.capture());
        assertThat(voteCaptor.getValue().getDecision()).isEqualTo("APPROVE");
        assertThat(voteCaptor.getValue().getActorId()).isEqualTo("operator");
        ArgumentCaptor<SoarRunEventEntity> eventCaptor = ArgumentCaptor.forClass(SoarRunEventEntity.class);
        verify(events).save(eventCaptor.capture());
        assertThat(eventCaptor.getValue().getEventType()).isEqualTo("APPROVAL_VOTE_RECORDED");
    }

    @Test
    void finalApprovalOnWorkflowGateSignalsTemporal() {
        SoarRunEntity wfRun = run("run-wf", "req-wf");
        wfRun.setTemporalWorkflowId("wf-1");
        SoarApprovalEntity approval = approval("apr-wf", "run-wf", "PENDING");
        given(runs.findByTenantIdAndIdForUpdate("tenant-a", "run-wf")).willReturn(Optional.of(wfRun));
        given(approvals.findByTenantIdAndIdForUpdate("tenant-a", "apr-wf"))
                .willReturn(Optional.of(approval));
        given(decisions.findByTenantIdAndApprovalIdOrderByCreatedAtAsc("tenant-a", "apr-wf"))
                .willReturn(List.of(vote("v-1", "apr-wf", "APPROVE"), vote("v-2", "apr-wf", "APPROVE")));

        Map<String, Object> view = service.decideApproval("apr-wf", true, "approved by quorum");

        assertThat(view).containsEntry("status", "APPROVED").containsEntry("approvedVotes", 2L);
        assertThat(approval.getStatus()).isEqualTo("APPROVED");
        ArgumentCaptor<SoarSignalOutboxEntity> signalCaptor =
                ArgumentCaptor.forClass(SoarSignalOutboxEntity.class);
        verify(signals).save(signalCaptor.capture());
        assertThat(signalCaptor.getValue().getSignalType()).isEqualTo("APPROVAL");
        assertThat(signalCaptor.getValue().getSignalKey()).isEqualTo("gate-apr-wf");
        ArgumentCaptor<SoarRunEventEntity> eventCaptor = ArgumentCaptor.forClass(SoarRunEventEntity.class);
        verify(events).save(eventCaptor.capture());
        assertThat(eventCaptor.getValue().getEventType()).isEqualTo("APPROVAL_GRANTED");
    }

    // -------------------------------------------------------------------- expiry janitor

    @Test
    void expireApprovalJanitorCoversAllBranches() {
        // unknown approval -> false
        given(approvals.findByTenantIdAndIdForUpdate("tenant-a", "apr-x")).willReturn(Optional.empty());
        given(approvals.findByTenantIdAndId("tenant-a", "apr-x")).willReturn(Optional.empty());
        assertThat(service.expireApproval("apr-x", Instant.now())).isFalse();

        // future expiry -> false
        SoarApprovalEntity future = approval("apr-exp", "run-lock", "PENDING");
        given(approvals.findByTenantIdAndIdForUpdate("tenant-a", "apr-exp"))
                .willReturn(Optional.of(future));
        assertThat(service.expireApproval("apr-exp", Instant.now())).isFalse();

        // missing owner run -> false
        SoarApprovalEntity expired = approval("apr-exp", "run-gone", "PENDING");
        expired.setExpiresAt(Instant.now().minusSeconds(60));
        given(approvals.findByTenantIdAndIdForUpdate("tenant-a", "apr-exp"))
                .willReturn(Optional.of(expired));
        given(runs.findByTenantIdAndIdForUpdate("tenant-a", "run-gone")).willReturn(Optional.empty());
        given(runs.findByTenantIdAndId("tenant-a", "run-gone")).willReturn(Optional.empty());
        assertThat(service.expireApproval("apr-exp", null)).isFalse();

        // success with an attached workflow -> signal queued
        SoarRunEntity wfRun = run("run-wf", "req-wf");
        wfRun.setStatus(SoarRunStatus.WAITING_APPROVAL.name());
        wfRun.setTemporalWorkflowId("wf-1");
        SoarApprovalEntity pending = approval("apr-exp", "run-wf", "PENDING");
        pending.setExpiresAt(Instant.now().minusSeconds(60));
        given(approvals.findByTenantIdAndIdForUpdate("tenant-a", "apr-exp"))
                .willReturn(Optional.of(pending));
        given(runs.findByTenantIdAndIdForUpdate("tenant-a", "run-wf")).willReturn(Optional.of(wfRun));

        assertThat(service.expireApproval("apr-exp", Instant.now())).isTrue();
        assertThat(pending.getStatus()).isEqualTo("EXPIRED");
        ArgumentCaptor<SoarApprovalDecisionEntity> voteCaptor =
                ArgumentCaptor.forClass(SoarApprovalDecisionEntity.class);
        verify(decisions).save(voteCaptor.capture());
        assertThat(voteCaptor.getValue().getDecision()).isEqualTo("EXPIRE");
        verify(signals).save(any(SoarSignalOutboxEntity.class));
        ArgumentCaptor<SoarRunEventEntity> eventCaptor = ArgumentCaptor.forClass(SoarRunEventEntity.class);
        verify(events).save(eventCaptor.capture());
        assertThat(eventCaptor.getValue().getEventType()).isEqualTo("APPROVAL_EXPIRED");
    }

    // ------------------------------------------------------- manual task form validation

    @Test
    void completeManualTaskRejectsBrokenAndNonObjectFormSchemas() {
        stubTask(task("t-bad", "{oops"));
        assertRejected(HttpStatus.BAD_REQUEST, "SOAR_MANUAL_INPUT_INVALID",
                () -> service.completeManualTask("t-bad", Map.of()));

        stubTask(task("t-arr", "[]"));
        assertRejected(HttpStatus.BAD_REQUEST, "SOAR_MANUAL_INPUT_INVALID",
                () -> service.completeManualTask("t-arr", Map.of()));
    }

    @Test
    void completeManualTaskRejectsOversizedInput() {
        stubTask(task("t-big", "{}"));
        Map<String, Object> input = Map.of("blob", "x".repeat(70 * 1024));
        assertRejected(HttpStatus.PAYLOAD_TOO_LARGE, "SOAR_MANUAL_INPUT_TOO_LARGE",
                () -> service.completeManualTask("t-big", input));
    }

    @Test
    void completeManualTaskRejectsDeeplyNestedInput() {
        stubTask(task("t-deep", nestedObjectSchema(22)));
        assertRejected(HttpStatus.BAD_REQUEST, "SOAR_MANUAL_INPUT_INVALID",
                () -> service.completeManualTask("t-deep", nestedObjectValue(22)));
    }

    @Test
    void booleanFormSchemasRejectOrAcceptTheWholeValue() {
        stubTask(task("t-false", "false"));
        assertRejected(HttpStatus.BAD_REQUEST, "SOAR_MANUAL_INPUT_INVALID",
                () -> service.completeManualTask("t-false", Map.of("k", "v")));

        // boolean-true schema accepts the value; the task then completes.
        stubTask(task("t-true", "true"));
        given(runs.findByTenantIdAndId("tenant-a", "run-1")).willReturn(Optional.of(run("run-1", "req-1")));
        Map<String, Object> view = service.completeManualTask("t-true", Map.of("k", "v"));
        assertThat(view).containsEntry("status", "COMPLETED");
        verify(signals).save(any(SoarSignalOutboxEntity.class));
    }

    @Test
    void manualSchemaRejectsEnumConstAndScalarTypeViolations() {
        stubTask(task("t-enum", "{\"enum\":[\"a\",\"b\"]}"));
        assertRejected(HttpStatus.BAD_REQUEST, "SOAR_MANUAL_INPUT_INVALID",
                () -> service.completeManualTask("t-enum", Map.of("v", "c")));

        stubTask(task("t-const", "{\"const\":\"x\"}"));
        assertRejected(HttpStatus.BAD_REQUEST, "SOAR_MANUAL_INPUT_INVALID",
                () -> service.completeManualTask("t-const", Map.of("v", "y")));

        stubTask(task("t-types", "{\"type\":[\"string\",\"number\"]}"));
        assertRejected(HttpStatus.BAD_REQUEST, "SOAR_MANUAL_INPUT_INVALID",
                () -> service.completeManualTask("t-types", Map.of("v", true)));

        stubTask(task("t-scalar-type", "{\"type\":123}"));
        assertRejected(HttpStatus.BAD_REQUEST, "SOAR_MANUAL_INPUT_INVALID",
                () -> service.completeManualTask("t-scalar-type", Map.of("v", "x")));

        stubTask(task("t-null-type", "{\"type\":\"null\"}"));
        assertRejected(HttpStatus.BAD_REQUEST, "SOAR_MANUAL_INPUT_INVALID",
                () -> service.completeManualTask("t-null-type", Map.of("v", "x")));

        stubTask(task("t-unknown-type", "{\"type\":\"mystery\"}"));
        assertRejected(HttpStatus.BAD_REQUEST, "SOAR_MANUAL_INPUT_INVALID",
                () -> service.completeManualTask("t-unknown-type", Map.of("v", "x")));
    }

    @Test
    void manualSchemaRejectsStringLengthAndPatternViolations() {
        stubTask(task("t-len", propSchema("{\"type\":\"string\",\"minLength\":5}")));
        assertRejected(HttpStatus.BAD_REQUEST, "SOAR_MANUAL_INPUT_INVALID",
                () -> service.completeManualTask("t-len", Map.of("v", "ab")));

        stubTask(task("t-pattern", propSchema("{\"type\":\"string\",\"pattern\":\"^a+$\"}")));
        assertRejected(HttpStatus.BAD_REQUEST, "SOAR_MANUAL_INPUT_INVALID",
                () -> service.completeManualTask("t-pattern", Map.of("v", "bbb")));

        stubTask(task("t-bad-pattern", propSchema("{\"type\":\"string\",\"pattern\":\"[\"}")));
        assertRejected(HttpStatus.BAD_REQUEST, "SOAR_MANUAL_INPUT_INVALID",
                () -> service.completeManualTask("t-bad-pattern", Map.of("v", "x")));
    }

    @Test
    void manualSchemaRejectsOutOfRangeNumbers() {
        stubTask(task("t-min", propSchema("{\"type\":\"number\",\"minimum\":5}")));
        assertRejected(HttpStatus.BAD_REQUEST, "SOAR_MANUAL_INPUT_INVALID",
                () -> service.completeManualTask("t-min", Map.of("v", 1)));

        stubTask(task("t-max", propSchema("{\"type\":\"number\",\"maximum\":5}")));
        assertRejected(HttpStatus.BAD_REQUEST, "SOAR_MANUAL_INPUT_INVALID",
                () -> service.completeManualTask("t-max", Map.of("v", 10)));

        stubTask(task("t-excl-min", propSchema("{\"type\":\"number\",\"exclusiveMinimum\":5}")));
        assertRejected(HttpStatus.BAD_REQUEST, "SOAR_MANUAL_INPUT_INVALID",
                () -> service.completeManualTask("t-excl-min", Map.of("v", 5)));

        stubTask(task("t-excl-max", propSchema("{\"type\":\"number\",\"exclusiveMaximum\":5}")));
        assertRejected(HttpStatus.BAD_REQUEST, "SOAR_MANUAL_INPUT_INVALID",
                () -> service.completeManualTask("t-excl-max", Map.of("v", 5)));
    }

    @Test
    void manualSchemaRejectsUnknownFieldsAndBadArrays() {
        stubTask(task("t-addl", "{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{}}"));
        assertRejected(HttpStatus.BAD_REQUEST, "SOAR_MANUAL_INPUT_INVALID",
                () -> service.completeManualTask("t-addl", Map.of("extra", 1)));

        stubTask(task("t-items", propSchema("{\"type\":\"array\",\"minItems\":3}")));
        assertRejected(HttpStatus.BAD_REQUEST, "SOAR_MANUAL_INPUT_INVALID",
                () -> service.completeManualTask("t-items", Map.of("v", List.of("a"))));

        stubTask(task("t-item-types", propSchema("{\"type\":\"array\",\"items\":{\"type\":\"string\"}}")));
        assertRejected(HttpStatus.BAD_REQUEST, "SOAR_MANUAL_INPUT_INVALID",
                () -> service.completeManualTask("t-item-types", Map.of("v", List.of("a", 2))));
    }

    @Test
    void completeManualTaskWithBlankNodeKeyFallsBackToLegacySignalLookup() {
        SoarManualTaskEntity task = task("t-blank", "{}");
        // Blank (not null) node id: the signal payload is built with Map.of,
        // which rejects null values, while a blank key still triggers the
        // legacy singleton-lookup fallback in enqueueSignal.
        task.setNodeId("");
        stubTask(task);
        SoarRunEntity owner = run("run-1", "req-1");
        owner.setTemporalWorkflowId("wf-9");
        given(runs.findByTenantIdAndId("tenant-a", "run-1")).willReturn(Optional.of(owner));

        Map<String, Object> view = service.completeManualTask("t-blank", Map.of());

        assertThat(view).containsEntry("status", "COMPLETED");
        ArgumentCaptor<SoarSignalOutboxEntity> signalCaptor =
                ArgumentCaptor.forClass(SoarSignalOutboxEntity.class);
        verify(signals).save(signalCaptor.capture());
        assertThat(signalCaptor.getValue().getSignalType()).isEqualTo("MANUAL_TASK");
        assertThat(signalCaptor.getValue().getSignalKey()).isEmpty();
        verify(signals).findByTenantIdAndRunIdAndSignalType("tenant-a", "run-1", "MANUAL_TASK");
    }

    // ------------------------------------------------------------------------ helpers

    private void stubAutomationTarget(String definition, String versionStatus, String playbookStatus) {
        given(versions.findByTenantIdAndId("tenant-a", "ver-1"))
                .willReturn(Optional.of(version("ver-1", "pb-1", versionStatus, definition)));
        given(playbooks.findByTenantIdAndId("tenant-a", "pb-1"))
                .willReturn(Optional.of(playbook("pb-1", playbookStatus)));
    }

    private void stubVersionLookup(String definition) {
        given(playbooks.findByTenantIdAndId("tenant-a", "pb-1"))
                .willReturn(Optional.of(playbook("pb-1", "ACTIVE")));
        given(versions.findByTenantIdAndPlaybookIdAndVersionNo("tenant-a", "pb-1", 3))
                .willReturn(Optional.of(version("ver-3", "pb-1", "DRAFT", definition)));
    }

    private void stubRegistryAction(String actionId, String risk, boolean requiresConnection) {
        ConnectorDescriptor descriptor = new ConnectorDescriptor("endpoint", 1, "Endpoint Response",
                true, List.of(actionDescriptor(actionId, requiresConnection, risk)));
        given(connectorRegistry.descriptorForAction("endpoint/custom-action"))
                .willReturn(Optional.of(descriptor));
        given(connectorRegistry.canonicalActionRef("endpoint/custom-action"))
                .willReturn("endpoint/custom-action");
    }

    private void stubApprovalLookup(SoarApprovalEntity approval) {
        given(approvals.findByTenantIdAndIdForUpdate("tenant-a", approval.getId()))
                .willReturn(Optional.of(approval));
    }

    private void stubTask(SoarManualTaskEntity task) {
        given(manualTasks.findByTenantIdAndIdForUpdate("tenant-a", task.getId()))
                .willReturn(Optional.of(task));
    }

    private static void assertRejected(HttpStatus status, String code, ThrowingCallable call) {
        Throwable thrown = catchThrowable(call);
        assertThat(thrown).isInstanceOf(ResponseStatusException.class);
        assertThat(((ResponseStatusException) thrown).getStatusCode()).isEqualTo(status);
        assertThat(thrown.getMessage()).contains(code);
    }

    private static DefinitionValidationResult validation(boolean valid, int highRiskActions) {
        return new DefinitionValidationResult(valid, List.of(), List.of(),
                SoarDefinitionValidator.SCHEMA_VERSION, "hash-1", 3, 1, highRiskActions);
    }

    private static ActionDescriptor actionDescriptor(String id, boolean requiresConnection, String risk) {
        return new ActionDescriptor(id, 1, id, id, risk, "REVERSIBLE", "NONE", requiresConnection,
                List.of("host"), Map.of(), Map.of());
    }

    private static String propSchema(String propertyJson) {
        return "{\"type\":\"object\",\"properties\":{\"v\":" + propertyJson + "}}";
    }

    private static String nestedObjectSchema(int depth) {
        StringBuilder sb = new StringBuilder();
        for (int index = 0; index < depth; index++) {
            sb.append("{\"type\":\"object\",\"properties\":{\"a\":");
        }
        sb.append("{\"type\":\"string\"}");
        for (int index = 0; index < depth; index++) {
            sb.append("}}");
        }
        return sb.toString();
    }

    private static Map<String, Object> nestedObjectValue(int depth) {
        Map<String, Object> leaf = new LinkedHashMap<>();
        leaf.put("a", "leaf");
        for (int index = 1; index < depth; index++) {
            Map<String, Object> next = new LinkedHashMap<>();
            next.put("a", leaf);
            leaf = next;
        }
        return leaf;
    }

    private static SoarRunEntity run(String id, String requestId) {
        SoarRunEntity run = new SoarRunEntity();
        run.setId(id);
        run.setTenantId("tenant-a");
        run.setRequestId(requestId);
        run.setExecutionSeriesId(id);
        run.setPlaybookId("pb-1");
        run.setPlaybookVersionId("ver-1");
        run.setPlaybookVersionNo(1);
        run.setDefinitionHash("hash-1");
        run.setTriggerType("MANUAL");
        run.setStatus(SoarRunStatus.QUEUED.name());
        run.setRequestedBy("alice");
        run.setInputJson("{\"subject\":{\"type\":\"alert\"},\"inputs\":{}}");
        run.setCreatedAt(Instant.now());
        run.setUpdatedAt(Instant.now());
        return run;
    }

    private static PlaybookVersionEntity version(String id, String playbookId, String status, String definition) {
        PlaybookVersionEntity version = new PlaybookVersionEntity();
        version.setId(id);
        version.setTenantId("tenant-a");
        version.setPlaybookId(playbookId);
        version.setVersionNo(1);
        version.setStatus(status);
        version.setSchemaVersion(SoarDefinitionValidator.SCHEMA_VERSION);
        version.setDefinitionJson(definition);
        version.setLayoutJson("{}");
        version.setDefinitionHash("hash-1");
        version.setRiskSummaryJson("{\"highRiskActionCount\":0,\"actionCount\":1}");
        version.setCreatedBy("alice");
        version.setCreatedAt(Instant.now());
        version.setUpdatedAt(Instant.now());
        return version;
    }

    private static SoarPlaybookEntity playbook(String id, String status) {
        SoarPlaybookEntity playbook = new SoarPlaybookEntity();
        playbook.setId(id);
        playbook.setTenantId("tenant-a");
        playbook.setName("Contain host");
        playbook.setOwner("alice");
        playbook.setStatus(status);
        playbook.setTagsJson("[]");
        playbook.setCreatedAt(Instant.now());
        playbook.setUpdatedAt(Instant.now());
        return playbook;
    }

    private static SoarApprovalEntity approval(String id, String runId, String status) {
        SoarApprovalEntity approval = new SoarApprovalEntity();
        approval.setId(id);
        approval.setTenantId("tenant-a");
        approval.setRunId(runId);
        approval.setApprovalKey("gate-" + id);
        approval.setStatus(status);
        approval.setRequestedBy("alice");
        approval.setRequiredApprovals(1);
        approval.setCreatedAt(Instant.now());
        approval.setExpiresAt(Instant.now().plusSeconds(3600));
        return approval;
    }

    private static SoarApprovalDecisionEntity vote(String id, String approvalId, String decision) {
        SoarApprovalDecisionEntity vote = new SoarApprovalDecisionEntity();
        vote.setId(id);
        vote.setTenantId("tenant-a");
        vote.setApprovalId(approvalId);
        vote.setActorId("operator");
        vote.setDecision(decision);
        vote.setReason("because");
        vote.setCreatedAt(Instant.now());
        return vote;
    }

    private static SoarConnectorEntity connection(String id, String type, boolean enabled) {
        SoarConnectorEntity connection = new SoarConnectorEntity();
        connection.setId(id);
        connection.setTenantId("tenant-a");
        connection.setName("Endpoint");
        connection.setConnectorType(type);
        connection.setEnabled(enabled);
        connection.setCreatedAt(Instant.now());
        connection.setUpdatedAt(Instant.now());
        return connection;
    }

    private static SoarManualTaskEntity task(String id, String schemaJson) {
        SoarManualTaskEntity task = new SoarManualTaskEntity();
        task.setId(id);
        task.setTenantId("tenant-a");
        task.setRunId("run-1");
        task.setNodeId("node-1");
        task.setFormSchemaJson(schemaJson);
        task.setStatus("PENDING");
        task.setCreatedAt(Instant.now());
        task.setDueAt(Instant.now().plusSeconds(3600));
        return task;
    }
}
