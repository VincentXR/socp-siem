package com.socp.soar.web.temporal.v2;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.socp.platform.tenant.context.TenantContext;
import com.socp.soar.web.connector.ActionQuery;
import com.socp.soar.web.connector.ActionResult;
import com.socp.soar.web.connector.EnvironmentSecretResolver;
import com.socp.soar.web.connector.SoarConnectorRegistry;
import com.socp.soar.web.persistence.entity.PlaybookVersionEntity;
import com.socp.soar.web.persistence.entity.SoarActionAttemptEntity;
import com.socp.soar.web.persistence.entity.SoarApprovalDecisionEntity;
import com.socp.soar.web.persistence.entity.SoarApprovalEntity;
import com.socp.soar.web.persistence.entity.SoarArtifactEntity;
import com.socp.soar.web.persistence.entity.SoarConnectorEntity;
import com.socp.soar.web.persistence.entity.SoarManualTaskEntity;
import com.socp.soar.web.persistence.entity.SoarNodeRunEntity;
import com.socp.soar.web.persistence.entity.SoarRunEntity;
import com.socp.soar.web.persistence.entity.SoarRunEventEntity;
import com.socp.soar.web.persistence.repository.PlaybookVersionRepository;
import com.socp.soar.web.persistence.repository.SoarActionAttemptRepository;
import com.socp.soar.web.persistence.repository.SoarApprovalDecisionRepository;
import com.socp.soar.web.persistence.repository.SoarApprovalRepository;
import com.socp.soar.web.persistence.repository.SoarArtifactRepository;
import com.socp.soar.web.persistence.repository.SoarConnectorRepository;
import com.socp.soar.web.persistence.repository.SoarManualTaskRepository;
import com.socp.soar.web.persistence.repository.SoarNodeRunRepository;
import com.socp.soar.web.persistence.repository.SoarRunEventRepository;
import com.socp.soar.web.persistence.repository.SoarRunRepository;
import com.socp.soar.web.service.PlaybookExecutor;
import com.socp.soar.web.temporal.request.ActionRequest;
import com.socp.soar.web.temporal.request.SoarV2NodeRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class SoarV2ActivityImplCoverageTest {

    private static final String TENANT = "tenant-a";
    private static final String RUN_ID = "run-1";
    private static final String NODE_ID = "node-1";
    private static final String APPROVAL_KEY = RUN_ID + ":node:" + NODE_ID;

    @Mock
    private PlaybookExecutor executor;
    @Mock
    private SoarRunRepository runs;
    @Mock
    private SoarNodeRunRepository nodeRuns;
    @Mock
    private SoarRunEventRepository events;
    @Mock
    private SoarApprovalRepository approvals;
    @Mock
    private SoarApprovalDecisionRepository approvalDecisions;
    @Mock
    private SoarActionAttemptRepository attempts;
    @Mock
    private SoarConnectorRepository connectors;
    @Mock
    private SoarConnectorRegistry connectorRegistry;
    @Mock
    private EnvironmentSecretResolver secretResolver;
    @Mock
    private SoarManualTaskRepository manualTasks;
    @Mock
    private PlaybookVersionRepository versions;
    @Mock
    private SoarArtifactRepository artifacts;

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    private SoarV2ActivityImpl activity;

    @BeforeEach
    void setUp() {
        TenantContext.set(TENANT);
        activity = newActivity(true);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private SoarV2ActivityImpl newActivity(boolean withOptionalCollaborators) {
        SoarV2ActivityImpl impl = new SoarV2ActivityImpl(executor, runs, nodeRuns, events, approvals, attempts,
                connectors, connectorRegistry, secretResolver, manualTasks, mapper);
        if (withOptionalCollaborators) {
            impl.setArtifacts(artifacts);
            impl.setApprovalDecisions(approvalDecisions);
        }
        return impl;
    }

    private SoarV2ActivityImpl newActivityWithVersions() {
        SoarV2ActivityImpl impl = new SoarV2ActivityImpl(executor, runs, nodeRuns, events, approvals, attempts,
                connectors, connectorRegistry, secretResolver, manualTasks, versions, mapper);
        impl.setArtifacts(artifacts);
        impl.setApprovalDecisions(approvalDecisions);
        return impl;
    }

    // ------------------------------------------------------------------
    // executeNode
    // ------------------------------------------------------------------

    @Test
    void executeNodePersistsSuccessfulAttemptNodeProjectionAndTimelineEvent() {
        SoarRunEntity run = run("RUNNING");
        givenRunLocked(run);
        givenNoPriorNodeRun();
        SoarActionAttemptEntity attempt = new SoarActionAttemptEntity();
        given(attempts.findByTenantIdAndNodeRunIdAndAttemptNo(anyString(), anyString(), anyInt()))
                .willReturn(Optional.of(attempt));
        given(events.findTopByTenantIdAndRunIdOrderBySequenceNoDesc(TENANT, RUN_ID)).willReturn(Optional.empty());
        given(events.findByTenantIdAndRunIdOrderBySequenceNoAsc(TENANT, RUN_ID))
                .willReturn(List.of(eventWithSequence(41L)));
        given(connectorRegistry.execute(any(ActionRequest.class)))
                .willReturn(ActionResult.success("op-1", Map.<String, Object>of("data", "ok"),
                        Map.<String, Object>of("httpStatus", 200)));

        SoarV2NodeResult result = activity.executeNode(
                nodeRequest("socp.alert/get", "{\"host\":\"web-1\",\"secret\":\"top\"}", 1));

        assertThat(result.status()).isEqualTo("SUCCEEDED");
        assertThat(result.errorCode()).isNull();
        assertThat(result.outputJson()).contains("op-1");
        assertThat(attempt.getStatus()).isEqualTo("SUCCEEDED");
        assertThat(attempt.getRemoteOperationId()).isEqualTo("op-1");
        assertThat(attempt.getReceiptJson()).contains("op-1");

        ArgumentCaptor<SoarNodeRunEntity> nodeCaptor = ArgumentCaptor.forClass(SoarNodeRunEntity.class);
        verify(nodeRuns).save(nodeCaptor.capture());
        SoarNodeRunEntity saved = nodeCaptor.getValue();
        assertThat(saved.getTenantId()).isEqualTo(TENANT);
        assertThat(saved.getRunId()).isEqualTo(RUN_ID);
        assertThat(saved.getNodeId()).isEqualTo(NODE_ID);
        assertThat(saved.getStatus()).isEqualTo("SUCCEEDED");
        assertThat(saved.getIdempotencyKey()).isEqualTo("idem-1");
        assertThat(saved.getInputJson()).contains("[REDACTED]").doesNotContain("top");
        assertThat(saved.getConnectionId()).isNull();
        assertThat(saved.getStartedAt()).isNotNull();
        assertThat(saved.getCompletedAt()).isNotNull();

        ArgumentCaptor<SoarRunEventEntity> eventCaptor = ArgumentCaptor.forClass(SoarRunEventEntity.class);
        verify(events).save(eventCaptor.capture());
        assertThat(eventCaptor.getValue().getEventType()).isEqualTo("NODE_SUCCEEDED");
        assertThat(eventCaptor.getValue().getSequenceNo()).isEqualTo(42L);
        assertThat(eventCaptor.getValue().getTenantId()).isEqualTo(TENANT);
    }

    @Test
    void executeNodeReusesSuccessfulPriorProjectionWithoutCallingTheConnector() {
        SoarNodeRunEntity prior = new SoarNodeRunEntity();
        prior.setId("noderun-1");
        prior.setStatus("SUCCEEDED");
        prior.setOutputJson("{\"status\":\"SUCCEEDED\"}");
        prior.setErrorCode(null);
        prior.setErrorMessage(null);
        given(nodeRuns.findByTenantIdAndRunIdAndNodeIdAndIterationPath(TENANT, RUN_ID, NODE_ID, ""))
                .willReturn(Optional.of(prior));

        SoarV2NodeResult result = activity.executeNode(nodeRequest("socp.alert/get", "{}", 1));

        assertThat(result.status()).isEqualTo("SUCCEEDED");
        assertThat(result.outputJson()).isEqualTo("{\"status\":\"SUCCEEDED\"}");
        verifyNoInteractions(connectorRegistry);
        verify(nodeRuns, never()).save(any(SoarNodeRunEntity.class));
    }

    @Test
    void executeNodeReusesConfirmedSuccessfulPriorProjection() {
        SoarNodeRunEntity prior = new SoarNodeRunEntity();
        prior.setId("noderun-2");
        prior.setStatus("CONFIRMED_SUCCEEDED");
        prior.setOutputJson("{\"status\":\"CONFIRMED_SUCCEEDED\"}");
        given(nodeRuns.findByTenantIdAndRunIdAndNodeIdAndIterationPath(TENANT, RUN_ID, NODE_ID, ""))
                .willReturn(Optional.of(prior));

        assertThat(activity.executeNode(nodeRequest("socp.alert/get", "{}", 1)).status())
                .isEqualTo("SUCCEEDED");
    }

    @Test
    void executeNodeMapsFailedActionToRetryableFailure() {
        SoarRunEntity run = run("RUNNING");
        givenRunLocked(run);
        givenNoPriorNodeRun();
        given(connectorRegistry.execute(any(ActionRequest.class)))
                .willReturn(ActionResult.failed("REMOTE_DENIED", "denied by vendor", true));

        SoarV2NodeResult result = activity.executeNode(nodeRequest("socp.alert/get", "{}", 1));

        assertThat(result.status()).isEqualTo("FAILED");
        assertThat(result.errorCode()).isEqualTo("REMOTE_DENIED");
        assertThat(result.errorMessage()).isEqualTo("denied by vendor");
        assertThat(result.retryable()).isTrue();
    }

    @Test
    void executeNodeReconcilesUnknownResultWhenTheConnectorCanProveTheOutcome() {
        SoarRunEntity run = run("RUNNING");
        givenRunLocked(run);
        givenNoPriorNodeRun();
        given(connectorRegistry.execute(any(ActionRequest.class)))
                .willReturn(ActionResult.unknown("REMOTE_RESULT_UNKNOWN", "transport timeout"));
        given(connectorRegistry.reconcile(any(ActionQuery.class)))
                .willReturn(Optional.of(ActionResult.success("op-9", Map.<String, Object>of("proof", true),
                        Map.<String, Object>of())));

        SoarV2NodeResult result = activity.executeNode(nodeRequest("endpoint/isolate-host", "{}", 1));

        assertThat(result.status()).isEqualTo("SUCCEEDED");
        assertThat(result.outputJson()).contains("op-9");
    }

    @Test
    void executeNodeKeepsUnknownWhenReconcileCannotProveTheOutcome() {
        SoarRunEntity run = run("RUNNING");
        givenRunLocked(run);
        givenNoPriorNodeRun();
        given(connectorRegistry.execute(any(ActionRequest.class)))
                .willReturn(ActionResult.unknown("REMOTE_RESULT_UNKNOWN", "transport timeout"));
        given(connectorRegistry.reconcile(any(ActionQuery.class))).willReturn(Optional.empty());

        SoarV2NodeResult result = activity.executeNode(nodeRequest("endpoint/isolate-host", "{}", 1));

        assertThat(result.status()).isEqualTo("UNKNOWN");
        assertThat(result.errorCode()).isEqualTo("REMOTE_RESULT_UNKNOWN");
        assertThat(result.retryable()).isFalse();
    }

    @Test
    void executeNodeFailsClosedWhenTheConnectionCannotBeResolved() {
        SoarRunEntity run = run("RUNNING");
        givenRunLocked(run);
        givenNoPriorNodeRun();
        given(connectors.findByTenantIdAndId(TENANT, "conn-1")).willReturn(Optional.empty());

        SoarV2NodeResult result = activity.executeNode(
                nodeRequestWithConnection("endpoint/isolate-host", "{}", 1, "conn-1"));

        assertThat(result.status()).isEqualTo("FAILED");
        assertThat(result.errorCode()).isEqualTo("SOAR_CONNECTION_UNAVAILABLE");
        assertThat(result.retryable()).isFalse();
    }

    @Test
    void executeNodeFailsClosedWhenTheConnectionIsDisabled() {
        SoarRunEntity run = run("RUNNING");
        givenRunLocked(run);
        givenNoPriorNodeRun();
        SoarConnectorEntity connector = connector();
        connector.setEnabled(false);
        given(connectors.findByTenantIdAndId(TENANT, "conn-1")).willReturn(Optional.of(connector));

        SoarV2NodeResult result = activity.executeNode(
                nodeRequestWithConnection("endpoint/isolate-host", "{}", 1, "conn-1"));

        assertThat(result.status()).isEqualTo("FAILED");
        assertThat(result.errorCode()).isEqualTo("SOAR_CONNECTION_UNAVAILABLE");
        assertThat(result.errorMessage()).contains("disabled");
        assertThat(result.retryable()).isFalse();
    }

    @Test
    void executeNodeAnnotatesTheAttemptAndProjectionWithTheConnectionRevision() {
        SoarRunEntity run = run("RUNNING");
        givenRunLocked(run);
        givenNoPriorNodeRun();
        given(connectors.findByTenantIdAndId(TENANT, "conn-1")).willReturn(Optional.of(connector()));
        given(connectorRegistry.execute(any(ActionRequest.class)))
                .willReturn(ActionResult.success("op-2", Map.<String, Object>of(), Map.<String, Object>of()));

        activity.executeNode(nodeRequestWithConnection("endpoint/isolate-host", "{}", 1, "conn-1"));

        ArgumentCaptor<ActionRequest> requestCaptor = ArgumentCaptor.forClass(ActionRequest.class);
        verify(connectorRegistry).execute(requestCaptor.capture());
        assertThat(requestCaptor.getValue().connection()).isNotNull();
        assertThat(requestCaptor.getValue().connection().connectionId()).isEqualTo("conn-1");
        assertThat(requestCaptor.getValue().connection().revision()).isEqualTo(3);
        assertThat(requestCaptor.getValue().connection().allowedHosts()).containsExactly("fw.example");

        ArgumentCaptor<SoarNodeRunEntity> nodeCaptor = ArgumentCaptor.forClass(SoarNodeRunEntity.class);
        verify(nodeRuns).save(nodeCaptor.capture());
        assertThat(nodeCaptor.getValue().getConnectionId()).isEqualTo("conn-1");
        assertThat(nodeCaptor.getValue().getConnectionRevision()).isEqualTo(3);
    }

    @Test
    void executeNodeFallsBackToTheLegacyExecutorForNonNamespacedActions() {
        SoarRunEntity run = run("RUNNING");
        givenRunLocked(run);
        givenNoPriorNodeRun();
        given(connectorRegistry.execute(any(ActionRequest.class)))
                .willReturn(ActionResult.failed("SOAR_ACTION_NOT_FOUND", "unknown action", false));
        given(executor.executeAction(eq("notify"), anyMap(), eq(false), anyInt()))
                .willReturn(Map.<String, Object>of("status", "executed", "operationId", "legacy-1"));

        SoarV2NodeResult result = activity.executeNode(nodeRequest("notify", "{}", 1));

        assertThat(result.status()).isEqualTo("SUCCEEDED");
        assertThat(result.outputJson()).contains("legacy-1");
    }

    @Test
    void executeNodeReportsRetryableExceptionWhenTheConnectorThrows() {
        SoarRunEntity run = run("RUNNING");
        givenRunLocked(run);
        givenNoPriorNodeRun();
        given(connectorRegistry.execute(any(ActionRequest.class))).willThrow(new IllegalStateException("boom"));

        SoarV2NodeResult result = activity.executeNode(nodeRequest("socp.alert/get", "{}", 1));

        assertThat(result.status()).isEqualTo("FAILED");
        assertThat(result.errorCode()).isEqualTo("ACTION_EXCEPTION");
        assertThat(result.errorMessage()).isEqualTo("boom");
        assertThat(result.retryable()).isTrue();
    }

    @Test
    void executeNodeDerivesTheAttemptNumberFromHistoricalAttempts() {
        SoarRunEntity run = run("RUNNING");
        givenRunLocked(run);
        givenNoPriorNodeRun();
        given(attempts.findByTenantIdAndNodeRunIdOrderByAttemptNoAsc(anyString(), anyString()))
                .willReturn(List.of(new SoarActionAttemptEntity(), new SoarActionAttemptEntity()));
        given(connectorRegistry.execute(any(ActionRequest.class)))
                .willReturn(ActionResult.success("op", Map.<String, Object>of(), Map.<String, Object>of()));

        activity.executeNode(nodeRequest("socp.alert/get", "{}", 0));

        ArgumentCaptor<SoarActionAttemptEntity> captor = ArgumentCaptor.forClass(SoarActionAttemptEntity.class);
        verify(attempts, atLeastOnce()).save(captor.capture());
        assertThat(captor.getAllValues()).anySatisfy(row -> assertThat(row.getAttemptNo()).isEqualTo(3));
    }

    @Test
    void executeNodeDoesNotInsertASecondAttemptRowOnRedelivery() {
        SoarRunEntity run = run("RUNNING");
        givenRunLocked(run);
        givenNoPriorNodeRun();
        SoarActionAttemptEntity existing = new SoarActionAttemptEntity();
        existing.setId("att-1");
        given(attempts.findByTenantIdAndNodeRunIdAndAttemptNoForUpdate(anyString(), anyString(), anyInt()))
                .willReturn(Optional.of(existing));
        given(attempts.findByTenantIdAndNodeRunIdAndAttemptNo(anyString(), anyString(), anyInt()))
                .willReturn(Optional.of(existing));
        given(connectorRegistry.execute(any(ActionRequest.class)))
                .willReturn(ActionResult.success("op", Map.<String, Object>of(), Map.<String, Object>of()));

        activity.executeNode(nodeRequest("socp.alert/get", "{}", 1));

        ArgumentCaptor<SoarActionAttemptEntity> captor = ArgumentCaptor.forClass(SoarActionAttemptEntity.class);
        verify(attempts, atLeastOnce()).save(captor.capture());
        assertThat(captor.getAllValues()).allSatisfy(row -> assertThat(row.getId()).isEqualTo("att-1"));
    }

    @Test
    void executeNodePersistsArtifactWhenTheInlineOutputLimitIsExceeded() {
        SoarRunEntity run = run("RUNNING");
        givenRunLocked(run);
        givenNoPriorNodeRun();
        String blob = "x".repeat(70_000);
        given(connectorRegistry.execute(any(ActionRequest.class)))
                .willReturn(ActionResult.success("op-3", Map.<String, Object>of("blob", blob),
                        Map.<String, Object>of()));
        given(artifacts.save(any(SoarArtifactEntity.class))).willAnswer(invocation -> invocation.getArgument(0));

        SoarV2NodeResult result = activity.executeNode(nodeRequest("socp.alert/get", "{}", 1));

        assertThat(result.status()).isEqualTo("SUCCEEDED");
        assertThat(result.outputJson()).contains("outputTruncated").contains("artifact").doesNotContain(blob);
        ArgumentCaptor<SoarArtifactEntity> captor = ArgumentCaptor.forClass(SoarArtifactEntity.class);
        verify(artifacts).save(captor.capture());
        SoarArtifactEntity artifact = captor.getValue();
        assertThat(artifact.getTenantId()).isEqualTo(TENANT);
        assertThat(artifact.getRunId()).isEqualTo(RUN_ID);
        assertThat(artifact.getSizeBytes()).isGreaterThan(65_536L);
        assertThat(artifact.getMediaType()).isEqualTo("application/json");
        assertThat(artifact.getClassification()).isEqualTo("INTERNAL");
        assertThat(artifact.getSha256()).hasSize(64);
        assertThat(artifact.getExpiresAt()).isAfter(Instant.now().plusSeconds(29L * 24 * 3600));
    }

    @Test
    void executeNodeFailsWhenLargeOutputHasNoArtifactStorage() {
        SoarV2ActivityImpl withoutArtifacts = newActivity(false);
        SoarRunEntity run = run("RUNNING");
        givenRunLocked(run);
        givenNoPriorNodeRun();
        given(connectorRegistry.execute(any(ActionRequest.class)))
                .willReturn(ActionResult.success("op-4",
                        Map.<String, Object>of("blob", "y".repeat(70_000)), Map.<String, Object>of()));

        SoarV2NodeResult result = withoutArtifacts.executeNode(nodeRequest("socp.alert/get", "{}", 1));

        assertThat(result.status()).isEqualTo("FAILED");
        assertThat(result.errorCode()).isEqualTo("SOAR_ARTIFACT_STORAGE_UNAVAILABLE");
        assertThat(result.retryable()).isFalse();
    }

    // ------------------------------------------------------------------
    // compensateNode
    // ------------------------------------------------------------------

    @Test
    void compensateNodeRequiresACompensationRef() {
        SoarV2NodeResult result = activity.compensateNode(
                nodeRequest("endpoint/isolate-host", "{}", 1), "   ");

        assertThat(result.status()).isEqualTo("FAILED");
        assertThat(result.errorCode()).isEqualTo("COMPENSATION_REF_REQUIRED");
        assertThat(result.retryable()).isFalse();
        verifyNoInteractions(events);
    }

    @Test
    void compensateNodeReportsUnavailableWhenTheConnectorCannotCompensate() {
        givenRunLocked(run("RUNNING"));
        given(connectorRegistry.compensate(any(ActionRequest.class), eq("endpoint/release-host")))
                .willReturn(Optional.empty());

        SoarV2NodeResult result = activity.compensateNode(
                nodeRequest("endpoint/isolate-host", "{}", 1), "endpoint/release-host");

        assertThat(result.status()).isEqualTo("FAILED");
        assertThat(result.errorCode()).isEqualTo("COMPENSATION_UNAVAILABLE");
        ArgumentCaptor<SoarRunEventEntity> captor = ArgumentCaptor.forClass(SoarRunEventEntity.class);
        verify(events).save(captor.capture());
        assertThat(captor.getValue().getEventType()).isEqualTo("ACTION_COMPENSATION_UNAVAILABLE");
    }

    @Test
    void compensateNodeRecordsAConnectorFailure() {
        givenRunLocked(run("RUNNING"));
        given(connectorRegistry.compensate(any(ActionRequest.class), anyString()))
                .willThrow(new IllegalStateException("vendor down"));

        SoarV2NodeResult result = activity.compensateNode(
                nodeRequest("endpoint/isolate-host", "{}", 1), "endpoint/release-host");

        assertThat(result.status()).isEqualTo("FAILED");
        assertThat(result.errorCode()).isEqualTo("COMPENSATION_FAILED");
        assertThat(result.errorMessage()).contains("vendor down");
        ArgumentCaptor<SoarRunEventEntity> captor = ArgumentCaptor.forClass(SoarRunEventEntity.class);
        verify(events).save(captor.capture());
        assertThat(captor.getValue().getEventType()).isEqualTo("ACTION_COMPENSATION_FAILED");
    }

    @Test
    void compensateNodePersistsTheCompensationReceipt() {
        givenRunLocked(run("RUNNING"));
        given(connectorRegistry.compensate(any(ActionRequest.class), anyString()))
                .willReturn(Optional.of(new ActionResult("SUCCEEDED", "comp-1",
                        Map.<String, Object>of("released", true), false, null, null, null,
                        Map.<String, Object>of("httpStatus", 200))));

        SoarV2NodeResult result = activity.compensateNode(
                nodeRequest("endpoint/isolate-host", "{}", 1), "endpoint/release-host");

        assertThat(result.status()).isEqualTo("SUCCEEDED");
        assertThat(result.outputJson()).contains("compensationRef").contains("released");
        ArgumentCaptor<SoarRunEventEntity> captor = ArgumentCaptor.forClass(SoarRunEventEntity.class);
        verify(events).save(captor.capture());
        assertThat(captor.getValue().getEventType()).isEqualTo("ACTION_COMPENSATION_SUCCEEDED");
    }

    // ------------------------------------------------------------------
    // run lifecycle
    // ------------------------------------------------------------------

    @Test
    void markRunStartedTransitionsAQueuedRunToRunning() {
        SoarRunEntity run = run("QUEUED");
        givenRunLocked(run);

        activity.markRunStarted(TENANT, RUN_ID);

        assertThat(run.getStatus()).isEqualTo("RUNNING");
        assertThat(run.getStartedAt()).isNotNull();
        verify(runs).save(run);
        ArgumentCaptor<SoarRunEventEntity> captor = ArgumentCaptor.forClass(SoarRunEventEntity.class);
        verify(events).save(captor.capture());
        assertThat(captor.getValue().getEventType()).isEqualTo("RUN_STARTED");
    }

    @Test
    void markRunStartedIsIdempotentForAnAlreadyRunningRun() {
        SoarRunEntity run = run("RUNNING");
        givenRunLocked(run);

        activity.markRunStarted(TENANT, RUN_ID);

        verify(runs, never()).save(any(SoarRunEntity.class));
        verify(events, never()).save(any(SoarRunEventEntity.class));
    }

    @Test
    void markRunStartedDoesNotResurrectATerminalProjection() {
        SoarRunEntity run = run("SUCCEEDED");
        givenRunLocked(run);

        activity.markRunStarted(TENANT, RUN_ID);

        assertThat(run.getStatus()).isEqualTo("SUCCEEDED");
        verify(runs, never()).save(any(SoarRunEntity.class));
        verify(events, never()).save(any(SoarRunEventEntity.class));
    }

    @Test
    void markRunStartedFailsWhenTheRunIsMissing() {
        assertThatThrownBy(() -> activity.markRunStarted(TENANT, "missing"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SOAR run not found");
    }

    @Test
    void markRunWaitingWithPolicyV2CreatesANodeScopedPendingApproval() {
        SoarRunEntity run = run("RUNNING");
        givenRunLocked(run);
        given(approvals.findByTenantIdAndApprovalKeyForUpdate(TENANT, APPROVAL_KEY)).willReturn(Optional.empty());

        activity.markRunWaitingWithPolicyV2(TENANT, RUN_ID, NODE_ID, 3600L, 3, "endpoint/isolate-host", "hash-1",
                "{\"approvalPolicy\":{\"roles\":[\"soc-admin\"]},\"host\":\"web-1\"}");

        assertThat(run.getStatus()).isEqualTo("WAITING_APPROVAL");
        ArgumentCaptor<SoarApprovalEntity> captor = ArgumentCaptor.forClass(SoarApprovalEntity.class);
        verify(approvals).save(captor.capture());
        SoarApprovalEntity approval = captor.getValue();
        assertThat(approval.getTenantId()).isEqualTo(TENANT);
        assertThat(approval.getApprovalKey()).isEqualTo(APPROVAL_KEY);
        assertThat(approval.getStatus()).isEqualTo("PENDING");
        assertThat(approval.getRequiredApprovals()).isEqualTo(3);
        assertThat(approval.getActionRef()).isEqualTo("endpoint/isolate-host");
        assertThat(approval.getInputHash()).isEqualTo("hash-1");
        assertThat(approval.getRequestedBy()).isEqualTo("alice");
        assertThat(approval.getTargetSnapshotJson()).contains("web-1");
        assertThat(approval.getPolicyJson()).contains("soc-admin");
        assertThat(approval.getExpiresAt()).isAfter(Instant.now().plusSeconds(3500L));
        ArgumentCaptor<SoarRunEventEntity> eventCaptor = ArgumentCaptor.forClass(SoarRunEventEntity.class);
        verify(events).save(eventCaptor.capture());
        assertThat(eventCaptor.getValue().getEventType()).isEqualTo("RUN_WAITING_APPROVAL");
    }

    @Test
    void markRunWaitingWithPolicyV2BackfillsAPendingApprovalAndBoundsTheTimeout() {
        SoarRunEntity run = run("RUNNING");
        givenRunLocked(run);
        SoarApprovalEntity existing = new SoarApprovalEntity();
        existing.setId("appr-1");
        existing.setTenantId(TENANT);
        existing.setStatus("PENDING");
        existing.setRequestedBy("workflow");
        given(approvals.findByTenantIdAndApprovalKeyForUpdate(TENANT, APPROVAL_KEY)).willReturn(Optional.of(existing));

        activity.markRunWaitingWithPolicyV2(TENANT, RUN_ID, NODE_ID, 0L, 0, "endpoint/isolate-host", "hash-2",
                "{\"host\":\"web-2\"}");

        assertThat(existing.getRequiredApprovals()).isEqualTo(1);
        assertThat(existing.getActionRef()).isEqualTo("endpoint/isolate-host");
        assertThat(existing.getInputHash()).isEqualTo("hash-2");
        assertThat(existing.getRequestedBy()).isEqualTo("alice");
        assertThat(existing.getTargetSnapshotJson()).contains("web-2");
        assertThat(existing.getPolicyJson()).isNull();
        assertThat(existing.getExpiresAt()).isAfter(Instant.now().plusSeconds(23L * 3600));
        verify(approvals).save(existing);
    }

    @Test
    void markRunWaitingWithPolicyV2NeverMutatesADecidedApproval() {
        SoarRunEntity run = run("RUNNING");
        givenRunLocked(run);
        SoarApprovalEntity existing = new SoarApprovalEntity();
        existing.setId("appr-2");
        existing.setStatus("APPROVED");
        given(approvals.findByTenantIdAndApprovalKeyForUpdate(TENANT, APPROVAL_KEY)).willReturn(Optional.of(existing));

        activity.markRunWaitingWithPolicyV2(TENANT, RUN_ID, NODE_ID, 60L, 2, "endpoint/isolate-host", "hash-3", "{}");

        assertThat(existing.getActionRef()).isNull();
        assertThat(existing.getInputHash()).isNull();
        verify(approvals, never()).save(any(SoarApprovalEntity.class));
    }

    @Test
    void markRunWaitingWithPolicyV2IgnoresACancellingRun() {
        SoarRunEntity run = run("CANCELLING");
        givenRunLocked(run);

        activity.markRunWaitingWithPolicyV2(TENANT, RUN_ID, NODE_ID, 60L, 1, "endpoint/isolate-host", "h", "{}");

        assertThat(run.getStatus()).isEqualTo("CANCELLING");
        verify(runs, never()).save(any(SoarRunEntity.class));
        verify(events, never()).save(any(SoarRunEventEntity.class));
    }

    @Test
    void markApprovalExpiredRecordsTheExpiryVote() {
        givenRunLocked(run("RUNNING"));
        SoarApprovalEntity pending = new SoarApprovalEntity();
        pending.setId("appr-1");
        pending.setTenantId(TENANT);
        pending.setStatus("PENDING");
        given(approvals.findByTenantIdAndApprovalKeyForUpdate(TENANT, APPROVAL_KEY)).willReturn(Optional.of(pending));

        activity.markApprovalExpired(TENANT, RUN_ID, NODE_ID);

        assertThat(pending.getStatus()).isEqualTo("EXPIRED");
        assertThat(pending.getDecidedAt()).isNotNull();
        assertThat(pending.getDecisionReason()).contains("expired");
        verify(approvals).save(pending);
        verify(approvalDecisions).save(any(SoarApprovalDecisionEntity.class));
        ArgumentCaptor<SoarRunEventEntity> captor = ArgumentCaptor.forClass(SoarRunEventEntity.class);
        verify(events).save(captor.capture());
        assertThat(captor.getValue().getEventType()).isEqualTo("APPROVAL_EXPIRED");
    }

    @Test
    void markApprovalExpiredIgnoresADecidedGate() {
        SoarApprovalEntity approved = new SoarApprovalEntity();
        approved.setId("appr-3");
        approved.setStatus("APPROVED");
        given(approvals.findByTenantIdAndApprovalKeyForUpdate(TENANT, APPROVAL_KEY)).willReturn(Optional.of(approved));

        activity.markApprovalExpired(TENANT, RUN_ID, NODE_ID);

        assertThat(approved.getStatus()).isEqualTo("APPROVED");
        verify(approvals, never()).save(any(SoarApprovalEntity.class));
        verify(approvalDecisions, never()).save(any(SoarApprovalDecisionEntity.class));
        verify(events, never()).save(any(SoarRunEventEntity.class));
    }

    @Test
    void markRunUnknownFlagsTheRunForOperatorResolution() {
        SoarRunEntity run = run("RUNNING");
        givenRunLocked(run);

        activity.markRunUnknown(TENANT, RUN_ID, NODE_ID);

        assertThat(run.getStatus()).isEqualTo("ACTION_UNKNOWN");
        assertThat(run.getErrorCode()).isEqualTo("SOAR_ACTION_RESULT_UNKNOWN");
        verify(runs).save(run);
        ArgumentCaptor<SoarRunEventEntity> captor = ArgumentCaptor.forClass(SoarRunEventEntity.class);
        verify(events).save(captor.capture());
        assertThat(captor.getValue().getEventType()).isEqualTo("ACTION_UNKNOWN");
    }

    @Test
    void markRunUnknownIgnoresACancellingRun() {
        SoarRunEntity run = run("CANCELLING");
        givenRunLocked(run);

        activity.markRunUnknown(TENANT, RUN_ID, NODE_ID);

        verify(runs, never()).save(any(SoarRunEntity.class));
        verify(events, never()).save(any(SoarRunEventEntity.class));
    }

    // ------------------------------------------------------------------
    // manual tasks
    // ------------------------------------------------------------------

    @Test
    void markManualTaskWaitingCreatesAPendingTask() {
        SoarRunEntity run = run("RUNNING");
        givenRunLocked(run);
        given(manualTasks.findByTenantIdAndRunIdAndNodeIdForUpdate(TENANT, RUN_ID, NODE_ID))
                .willReturn(Optional.empty());

        activity.markManualTaskWaiting(TENANT, RUN_ID, NODE_ID, "{\"type\":\"object\"}", "bob",
                "2030-01-01T00:00:00Z");

        assertThat(run.getStatus()).isEqualTo("WAITING_INPUT");
        ArgumentCaptor<SoarManualTaskEntity> captor = ArgumentCaptor.forClass(SoarManualTaskEntity.class);
        verify(manualTasks).save(captor.capture());
        SoarManualTaskEntity task = captor.getValue();
        assertThat(task.getTenantId()).isEqualTo(TENANT);
        assertThat(task.getNodeId()).isEqualTo(NODE_ID);
        assertThat(task.getStatus()).isEqualTo("PENDING");
        assertThat(task.getAssignee()).isEqualTo("bob");
        assertThat(task.getDueAt()).isEqualTo(Instant.parse("2030-01-01T00:00:00Z"));
        ArgumentCaptor<SoarRunEventEntity> eventCaptor = ArgumentCaptor.forClass(SoarRunEventEntity.class);
        verify(events).save(eventCaptor.capture());
        assertThat(eventCaptor.getValue().getEventType()).isEqualTo("RUN_WAITING_INPUT");
    }

    @Test
    void markManualTaskWaitingDefaultsTheSchemaAndDueDate() {
        SoarRunEntity run = run("RUNNING");
        givenRunLocked(run);
        given(manualTasks.findByTenantIdAndRunIdAndNodeIdForUpdate(TENANT, RUN_ID, NODE_ID))
                .willReturn(Optional.empty());

        activity.markManualTaskWaiting(TENANT, RUN_ID, NODE_ID, "  ", "   ", "not-a-date");

        ArgumentCaptor<SoarManualTaskEntity> captor = ArgumentCaptor.forClass(SoarManualTaskEntity.class);
        verify(manualTasks).save(captor.capture());
        assertThat(captor.getValue().getFormSchemaJson()).isEqualTo("{\"type\":\"object\"}");
        assertThat(captor.getValue().getAssignee()).isNull();
        assertThat(captor.getValue().getDueAt()).isAfter(Instant.now().plusSeconds(86_000));
    }

    @Test
    void markManualTaskWaitingReusesAnExistingTask() {
        SoarRunEntity run = run("RUNNING");
        givenRunLocked(run);
        SoarManualTaskEntity existing = new SoarManualTaskEntity();
        existing.setId("task-1");
        given(manualTasks.findByTenantIdAndRunIdAndNodeIdForUpdate(TENANT, RUN_ID, NODE_ID))
                .willReturn(Optional.of(existing));

        activity.markManualTaskWaiting(TENANT, RUN_ID, NODE_ID, "{}", null, null);

        verify(manualTasks, never()).save(any(SoarManualTaskEntity.class));
        verify(events).save(any(SoarRunEventEntity.class));
    }

    @Test
    void markManualTaskExpiredExpiresThePendingTask() {
        givenRunLocked(run("WAITING_INPUT"));
        SoarManualTaskEntity task = new SoarManualTaskEntity();
        task.setId("task-1");
        task.setStatus("PENDING");
        given(manualTasks.findByTenantIdAndRunIdAndNodeIdForUpdate(TENANT, RUN_ID, NODE_ID))
                .willReturn(Optional.of(task));

        activity.markManualTaskExpired(TENANT, RUN_ID, NODE_ID);

        assertThat(task.getStatus()).isEqualTo("EXPIRED");
        verify(manualTasks).save(task);
        ArgumentCaptor<SoarRunEventEntity> captor = ArgumentCaptor.forClass(SoarRunEventEntity.class);
        verify(events).save(captor.capture());
        assertThat(captor.getValue().getEventType()).isEqualTo("MANUAL_TASK_EXPIRED");
        assertThat(captor.getValue().getNodeRunId()).isEqualTo("task-1");
    }

    // ------------------------------------------------------------------
    // recordNode / markRunCompleted / resolvePublishedDefinition
    // ------------------------------------------------------------------

    @Test
    void recordNodePersistsARedactedProjection() {
        givenRunLocked(run("RUNNING"));
        givenNoPriorNodeRun();

        activity.recordNode(nodeRequest("socp.alert/get", "{\"password\":\"p@ss\"}", 1),
                new SoarV2NodeResult("SUCCEEDED", "{\"token\":\"abc\"}", null, null));

        ArgumentCaptor<SoarNodeRunEntity> captor = ArgumentCaptor.forClass(SoarNodeRunEntity.class);
        verify(nodeRuns).save(captor.capture());
        SoarNodeRunEntity row = captor.getValue();
        assertThat(row.getId()).isNotNull();
        assertThat(row.getTenantId()).isEqualTo(TENANT);
        assertThat(row.getIterationPath()).isEmpty();
        assertThat(row.getStatus()).isEqualTo("SUCCEEDED");
        assertThat(row.getInputJson()).contains("[REDACTED]").doesNotContain("p@ss");
        assertThat(row.getOutputJson()).contains("[REDACTED]").doesNotContain("abc");
        ArgumentCaptor<SoarRunEventEntity> eventCaptor = ArgumentCaptor.forClass(SoarRunEventEntity.class);
        verify(events).save(eventCaptor.capture());
        assertThat(eventCaptor.getValue().getEventType()).isEqualTo("NODE_SUCCEEDED");
    }

    @Test
    void markRunCompletedPersistsTheTerminalProjection() {
        SoarRunEntity run = run("RUNNING");
        givenRunLocked(run);

        activity.markRunCompleted(new SoarV2RunUpdate(TENANT, RUN_ID, "SUCCEEDED", "{\"a\":1}", null, null));

        assertThat(run.getStatus()).isEqualTo("SUCCEEDED");
        assertThat(run.getOutputJson()).contains("\"a\":1");
        assertThat(run.getCompletedAt()).isNotNull();
        verify(runs).save(run);
        ArgumentCaptor<SoarRunEventEntity> captor = ArgumentCaptor.forClass(SoarRunEventEntity.class);
        verify(events).save(captor.capture());
        assertThat(captor.getValue().getEventType()).isEqualTo("RUN_SUCCEEDED");
    }

    @Test
    void markRunCompletedIgnoresALateCompletionAfterAnOperatorTerminalDecision() {
        SoarRunEntity run = run("CANCELLED");
        run.setErrorCode("SOAR_RUN_CANCELLED");
        givenRunLocked(run);

        activity.markRunCompleted(new SoarV2RunUpdate(TENANT, RUN_ID, "SUCCEEDED", "{}", null, null));

        assertThat(run.getStatus()).isEqualTo("CANCELLED");
        verify(runs, never()).save(any(SoarRunEntity.class));
        ArgumentCaptor<SoarRunEventEntity> captor = ArgumentCaptor.forClass(SoarRunEventEntity.class);
        verify(events).save(captor.capture());
        assertThat(captor.getValue().getEventType()).isEqualTo("RUN_COMPLETION_IGNORED");
    }

    @Test
    void markRunCompletedFailsWhenTheRunIsMissing() {
        assertThatThrownBy(() -> activity.markRunCompleted(
                new SoarV2RunUpdate(TENANT, "missing", "SUCCEEDED", "{}", null, null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SOAR run not found");
    }

    @Test
    void resolvePublishedDefinitionReturnsTheImmutableDefinition() {
        SoarV2ActivityImpl withVersions = newActivityWithVersions();
        PlaybookVersionEntity version = new PlaybookVersionEntity();
        version.setId("ver-1");
        version.setStatus("PUBLISHED");
        version.setDefinitionJson("{\"nodes\":[]}");
        given(versions.findByTenantIdAndId(TENANT, "ver-1")).willReturn(Optional.of(version));

        assertThat(withVersions.resolvePublishedDefinition(TENANT, "ver-1")).isEqualTo("{\"nodes\":[]}");
    }

    @Test
    void resolvePublishedDefinitionRejectsAMissingOrUnpublishedVersion() {
        SoarV2ActivityImpl withVersions = newActivityWithVersions();
        PlaybookVersionEntity draft = new PlaybookVersionEntity();
        draft.setId("ver-2");
        draft.setStatus("DRAFT");
        draft.setDefinitionJson("{}");
        given(versions.findByTenantIdAndId(TENANT, "ver-2")).willReturn(Optional.of(draft));
        given(versions.findByTenantIdAndId(TENANT, "ver-3")).willReturn(Optional.empty());

        assertThatThrownBy(() -> withVersions.resolvePublishedDefinition(TENANT, "ver-2"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SOAR_SUB_PLAYBOOK_NOT_FOUND");
        assertThatThrownBy(() -> withVersions.resolvePublishedDefinition(TENANT, "ver-3"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SOAR_SUB_PLAYBOOK_NOT_FOUND");
    }

    @Test
    void resolvePublishedDefinitionRejectsABlankDefinition() {
        SoarV2ActivityImpl withVersions = newActivityWithVersions();
        PlaybookVersionEntity version = new PlaybookVersionEntity();
        version.setId("ver-4");
        version.setStatus("PUBLISHED");
        version.setDefinitionJson("   ");
        given(versions.findByTenantIdAndId(TENANT, "ver-4")).willReturn(Optional.of(version));

        assertThatThrownBy(() -> withVersions.resolvePublishedDefinition(TENANT, "ver-4"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SOAR_SUB_PLAYBOOK_DEFINITION_INVALID");
    }

    @Test
    void resolvePublishedDefinitionFailsClosedWithoutAVersionStore() {
        assertThatThrownBy(() -> activity.resolvePublishedDefinition(TENANT, "ver-1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SOAR_SUB_PLAYBOOK_UNAVAILABLE");
    }

    @Test
    void resolvePublishedDefinitionRejectsABlankTenantOrVersion() {
        assertThatThrownBy(() -> activity.resolvePublishedDefinition("  ", "ver-1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SOAR_SUB_PLAYBOOK_UNAVAILABLE");
        assertThatThrownBy(() -> activity.resolvePublishedDefinition(TENANT, ""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SOAR_SUB_PLAYBOOK_UNAVAILABLE");
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private void givenRunLocked(SoarRunEntity run) {
        given(runs.findByTenantIdAndIdForUpdate(TENANT, RUN_ID)).willReturn(Optional.of(run));
    }

    private void givenNoPriorNodeRun() {
        given(nodeRuns.findByTenantIdAndRunIdAndNodeIdAndIterationPath(TENANT, RUN_ID, NODE_ID, ""))
                .willReturn(Optional.empty());
    }

    private static SoarRunEntity run(String status) {
        SoarRunEntity run = new SoarRunEntity();
        run.setId(RUN_ID);
        run.setTenantId(TENANT);
        run.setStatus(status);
        run.setRequestedBy("alice");
        return run;
    }

    private static SoarConnectorEntity connector() {
        SoarConnectorEntity connector = new SoarConnectorEntity();
        connector.setId("conn-1");
        connector.setTenantId(TENANT);
        connector.setEnabled(true);
        connector.setRevision(3);
        connector.setConnectorType("endpoint");
        connector.setEndpoint("https://fw.example/isolate");
        connector.setConfigJson("{\"zone\":\"dmz\"}");
        connector.setSecretRefsJson("{\"auth\":\"secret://fw/token\"}");
        connector.setAuthSecretRef("secret://fw/token");
        connector.setAllowedHostsJson("[\"fw.example\"]");
        return connector;
    }

    private static SoarRunEventEntity eventWithSequence(long sequence) {
        SoarRunEventEntity event = new SoarRunEventEntity();
        event.setId("event-1");
        event.setSequenceNo(sequence);
        return event;
    }

    private static SoarV2NodeRequest nodeRequest(String actionRef, String inputJson, int attemptNo) {
        return nodeRequestWithConnection(actionRef, inputJson, attemptNo, "");
    }

    private static SoarV2NodeRequest nodeRequestWithConnection(String actionRef, String inputJson,
                                                               int attemptNo, String connectionRef) {
        return new SoarV2NodeRequest(TENANT, RUN_ID, NODE_ID, "ACTION", actionRef, "", inputJson,
                "idem-1", connectionRef, Map.<String, Object>of("id", "alert-9"), attemptNo);
    }
}
