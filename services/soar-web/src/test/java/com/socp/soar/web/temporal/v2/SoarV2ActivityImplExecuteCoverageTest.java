package com.socp.soar.web.temporal.v2;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.socp.platform.tenant.context.TenantContext;
import com.socp.soar.web.connector.ActionRequest;
import com.socp.soar.web.connector.ActionResult;
import com.socp.soar.web.connector.EnvironmentSecretResolver;
import com.socp.soar.web.connector.SoarConnectorRegistry;
import com.socp.soar.web.persistence.entity.PlaybookVersionEntity;
import com.socp.soar.web.persistence.entity.SoarApprovalDecisionEntity;
import com.socp.soar.web.persistence.entity.SoarApprovalEntity;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Additional deterministic coverage for SoarV2ActivityImpl paths that the
 * existing SoarV2ActivityImplCoverageTest does not reach (lock-projection
 * fallbacks, hard output limit, reconciled FAILED outcomes, delegation
 * methods, oversized child definitions and prior-row reuse).
 */
@ExtendWith(MockitoExtension.class)
class SoarV2ActivityImplExecuteCoverageTest {

    private static final String TENANT = "tenant-a";
    private static final String TENANT_B = "tenant-b";
    private static final String RUN_ID = "run-9";
    private static final String NODE_ID = "node-9";
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

    private final ObjectMapper mapper = new ObjectMapper();

    private SoarV2ActivityImpl activity;

    @BeforeEach
    void setUp() {
        TenantContext.set(TENANT);
        activity = new SoarV2ActivityImpl(executor, runs, nodeRuns, events, approvals, attempts,
                connectors, connectorRegistry, secretResolver, manualTasks, versions, mapper);
        activity.setArtifacts(artifacts);
        activity.setApprovalDecisions(approvalDecisions);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    // ------------------------------------------------------------------
    // delegation + lock-projection fallbacks
    // ------------------------------------------------------------------

    @Test
    void markRunWaitingDelegatesWithDefaultTimeoutAndSingleApproval() {
        SoarRunEntity run = run("RUNNING");
        givenRunLocked(run);
        given(approvals.findByTenantIdAndApprovalKeyForUpdate(TENANT, APPROVAL_KEY))
                .willReturn(Optional.empty());

        activity.markRunWaiting(TENANT, RUN_ID, NODE_ID);

        assertThat(run.getStatus()).isEqualTo("WAITING_APPROVAL");
        ArgumentCaptor<SoarApprovalEntity> captor = ArgumentCaptor.forClass(SoarApprovalEntity.class);
        verify(approvals).save(captor.capture());
        assertThat(captor.getValue().getRequiredApprovals()).isEqualTo(1);
        assertThat(captor.getValue().getExpiresAt()).isAfter(Instant.now().plusSeconds(23L * 3600));
        assertThat(captor.getValue().getStatus()).isEqualTo("PENDING");
    }

    @Test
    void markRunWaitingWithPolicyBoundsTheTimeoutAndClampsRequiredApprovals() {
        SoarRunEntity run = run("RUNNING");
        givenRunLocked(run);
        given(approvals.findByTenantIdAndApprovalKeyForUpdate(TENANT, APPROVAL_KEY))
                .willReturn(Optional.empty());

        activity.markRunWaitingWithPolicy(TENANT, RUN_ID, NODE_ID, 10_000_000L, 50);

        ArgumentCaptor<SoarApprovalEntity> captor = ArgumentCaptor.forClass(SoarApprovalEntity.class);
        verify(approvals).save(captor.capture());
        assertThat(captor.getValue().getRequiredApprovals()).isEqualTo(20);
        assertThat(captor.getValue().getExpiresAt())
                .isAfter(Instant.now().plusSeconds(6L * 24 * 3600))
                .isBefore(Instant.now().plusSeconds(8L * 24 * 3600));
    }

    @Test
    void markRunStartedFallsBackWhenTheLockProjectionIsMissing() {
        SoarRunEntity run = run("QUEUED");
        given(runs.findByTenantIdAndIdForUpdate(TENANT, RUN_ID)).willReturn(null);
        given(runs.findByTenantIdAndId(TENANT, RUN_ID)).willReturn(Optional.of(run));

        activity.markRunStarted(TENANT, RUN_ID);

        assertThat(run.getStatus()).isEqualTo("RUNNING");
        verify(runs).save(run);
        ArgumentCaptor<SoarRunEventEntity> captor = ArgumentCaptor.forClass(SoarRunEventEntity.class);
        verify(events).save(captor.capture());
        assertThat(captor.getValue().getEventType()).isEqualTo("RUN_STARTED");
    }

    @Test
    void markManualTaskExpiredFallsBackToPlainLookupAndExpiresThePendingTask() {
        givenRunLocked(run("RUNNING"));
        SoarManualTaskEntity task = new SoarManualTaskEntity();
        task.setId("task-9");
        task.setStatus("PENDING");
        given(manualTasks.findByTenantIdAndRunIdAndNodeIdForUpdate(TENANT, RUN_ID, NODE_ID)).willReturn(null);
        given(manualTasks.findByTenantIdAndRunIdAndNodeId(TENANT, RUN_ID, NODE_ID)).willReturn(Optional.of(task));

        activity.markManualTaskExpired(TENANT, RUN_ID, NODE_ID);

        assertThat(task.getStatus()).isEqualTo("EXPIRED");
        verify(manualTasks).save(task);
        ArgumentCaptor<SoarRunEventEntity> captor = ArgumentCaptor.forClass(SoarRunEventEntity.class);
        verify(events).save(captor.capture());
        assertThat(captor.getValue().getEventType()).isEqualTo("MANUAL_TASK_EXPIRED");
    }

    // ------------------------------------------------------------------
    // executeNode corner paths
    // ------------------------------------------------------------------

    @Test
    void executeNodeRetriesAFailedNodeRunUsingThePriorIdentity() {
        givenRunLocked(run("RUNNING"));
        SoarNodeRunEntity prior = new SoarNodeRunEntity();
        prior.setId("noderun-9");
        prior.setStatus("FAILED");
        given(nodeRuns.findByTenantIdAndRunIdAndNodeIdAndIterationPath(TENANT, RUN_ID, NODE_ID, ""))
                .willReturn(Optional.of(prior));
        given(connectorRegistry.execute(any(ActionRequest.class)))
                .willReturn(ActionResult.success("op-9", Map.<String, Object>of("ok", true),
                        Map.<String, Object>of()));

        SoarV2NodeResult result = activity.executeNode(nodeRequest(TENANT, "{\"k\":\"v\"}", 1));

        assertThat(result.status()).isEqualTo("SUCCEEDED");
        ArgumentCaptor<SoarNodeRunEntity> captor = ArgumentCaptor.forClass(SoarNodeRunEntity.class);
        verify(nodeRuns).save(captor.capture());
        assertThat(captor.getValue().getId()).isEqualTo("noderun-9");
        assertThat(captor.getValue().getStatus()).isEqualTo("SUCCEEDED");
        assertThat(captor.getValue().getTenantId()).isEqualTo(TENANT);
    }

    @Test
    void executeNodeAdoptsAFailedReconciledOutcome() {
        givenRunLocked(run("RUNNING"));
        givenNoPriorNodeRun();
        given(connectorRegistry.execute(any(ActionRequest.class)))
                .willReturn(ActionResult.unknown("REMOTE_RESULT_UNKNOWN", "transport timeout"));
        given(connectorRegistry.reconcile(any(com.socp.soar.web.connector.ActionQuery.class)))
                .willReturn(Optional.of(ActionResult.failed("VENDOR_REJECTED", "rejected by vendor", false)));

        SoarV2NodeResult result = activity.executeNode(nodeRequest(TENANT, "{}", 1));

        assertThat(result.status()).isEqualTo("FAILED");
        assertThat(result.errorCode()).isEqualTo("VENDOR_REJECTED");
        assertThat(result.errorMessage()).isEqualTo("rejected by vendor");
        assertThat(result.retryable()).isFalse();
    }

    @Test
    void executeNodeFailsWhenTheOutputExceedsTheHardLimit() {
        givenRunLocked(run("RUNNING"));
        givenNoPriorNodeRun();
        given(connectorRegistry.execute(any(ActionRequest.class)))
                .willReturn(ActionResult.success("op-big",
                        Map.<String, Object>of("blob", "z".repeat(11_000_000)), Map.<String, Object>of()));

        SoarV2NodeResult result = activity.executeNode(nodeRequest(TENANT, "{}", 1));

        assertThat(result.status()).isEqualTo("FAILED");
        assertThat(result.errorCode()).isEqualTo("SOAR_OUTPUT_TOO_LARGE");
        assertThat(result.errorMessage()).contains("10 MiB");
        assertThat(result.retryable()).isFalse();
        ArgumentCaptor<SoarNodeRunEntity> captor = ArgumentCaptor.forClass(SoarNodeRunEntity.class);
        verify(nodeRuns).save(captor.capture());
        assertThat(captor.getValue().getErrorCode()).isEqualTo("SOAR_OUTPUT_TOO_LARGE");
        verify(artifacts, never()).save(any(com.socp.soar.web.persistence.entity.SoarArtifactEntity.class));
    }

    @Test
    void executeNodeNormalizesConnectionRevisionAndAppliesTheAuthSecretRefDefault() {
        givenRunLocked(run("RUNNING"));
        givenNoPriorNodeRun();
        SoarConnectorEntity connector = connector();
        connector.setRevision(0);
        connector.setAuthSecretRef("   ");
        given(connectors.findByTenantIdAndId(TENANT, "conn-1")).willReturn(Optional.of(connector));
        given(connectorRegistry.execute(any(ActionRequest.class)))
                .willReturn(ActionResult.success("op-conn", Map.<String, Object>of(), Map.<String, Object>of()));

        activity.executeNode(nodeRequestWithConnection(TENANT, "{}", 1, "conn-1"));

        ArgumentCaptor<ActionRequest> requestCaptor = ArgumentCaptor.forClass(ActionRequest.class);
        verify(connectorRegistry).execute(requestCaptor.capture());
        assertThat(requestCaptor.getValue().connection().revision()).isEqualTo(1);
        assertThat(requestCaptor.getValue().connection().connectionId()).isEqualTo("conn-1");
        ArgumentCaptor<SoarNodeRunEntity> nodeCaptor = ArgumentCaptor.forClass(SoarNodeRunEntity.class);
        verify(nodeRuns).save(nodeCaptor.capture());
        assertThat(nodeCaptor.getValue().getConnectionRevision()).isEqualTo(1);
    }

    @Test
    void executeNodeIsTenantScopedToTheRequestingTenant() {
        givenRunLocked(runFor(TENANT_B, "RUNNING"));
        given(nodeRuns.findByTenantIdAndRunIdAndNodeIdAndIterationPath(TENANT_B, RUN_ID, NODE_ID, ""))
                .willReturn(Optional.empty());
        given(connectorRegistry.execute(any(ActionRequest.class)))
                .willReturn(ActionResult.success("op-tb", Map.<String, Object>of(), Map.<String, Object>of()));

        SoarV2NodeResult result = activity.executeNode(nodeRequest(TENANT_B, "{}", 1));

        assertThat(result.status()).isEqualTo("SUCCEEDED");
        ArgumentCaptor<SoarNodeRunEntity> nodeCaptor = ArgumentCaptor.forClass(SoarNodeRunEntity.class);
        verify(nodeRuns).save(nodeCaptor.capture());
        assertThat(nodeCaptor.getValue().getTenantId()).isEqualTo(TENANT_B);
        ArgumentCaptor<SoarRunEventEntity> eventCaptor = ArgumentCaptor.forClass(SoarRunEventEntity.class);
        verify(events).save(eventCaptor.capture());
        assertThat(eventCaptor.getValue().getTenantId()).isEqualTo(TENANT_B);
    }

    // ------------------------------------------------------------------
    // compensateNode connector-reported failure
    // ------------------------------------------------------------------

    @Test
    void compensateNodeSurfacesConnectorReportedFailuresWithRedaction() {
        givenRunLocked(run("RUNNING"));
        given(connectorRegistry.compensate(any(ActionRequest.class), eq("endpoint/release-host")))
                .willReturn(Optional.of(new ActionResult("FAILED", null,
                        Map.<String, Object>of(), false, "VENDOR_BUSY", "busy: Bearer abc.secret",
                        null, Map.<String, Object>of())));

        SoarV2NodeResult result = activity.compensateNode(
                nodeRequest(TENANT, "{}", 1), "endpoint/release-host");

        assertThat(result.status()).isEqualTo("FAILED");
        assertThat(result.errorCode()).isEqualTo("VENDOR_BUSY");
        assertThat(result.errorMessage()).contains("Bearer [REDACTED]").doesNotContain("abc.secret");
        ArgumentCaptor<SoarRunEventEntity> captor = ArgumentCaptor.forClass(SoarRunEventEntity.class);
        verify(events).save(captor.capture());
        assertThat(captor.getValue().getEventType()).isEqualTo("ACTION_COMPENSATION_FAILED");
    }

    // ------------------------------------------------------------------
    // recordNode prior-row update path
    // ------------------------------------------------------------------

    @Test
    void recordNodeUpdatesAnExistingNodeRunRow() {
        givenRunLocked(run("RUNNING"));
        SoarNodeRunEntity existing = new SoarNodeRunEntity();
        existing.setId("noderun-7");
        existing.setStatus("RUNNING");
        given(nodeRuns.findByTenantIdAndRunIdAndNodeIdAndIterationPath(TENANT, RUN_ID, NODE_ID, ""))
                .willReturn(Optional.of(existing));

        activity.recordNode(nodeRequest(TENANT, "{}", 1),
                new SoarV2NodeResult("FAILED", "{}", "E-X", "bad"));

        assertThat(existing.getStatus()).isEqualTo("FAILED");
        assertThat(existing.getErrorCode()).isEqualTo("E-X");
        assertThat(existing.getCompletedAt()).isNotNull();
        verify(nodeRuns).save(existing);
        ArgumentCaptor<SoarRunEventEntity> captor = ArgumentCaptor.forClass(SoarRunEventEntity.class);
        verify(events).save(captor.capture());
        assertThat(captor.getValue().getEventType()).isEqualTo("NODE_FAILED");
    }

    // ------------------------------------------------------------------
    // resolvePublishedDefinition size limit
    // ------------------------------------------------------------------

    @Test
    void resolvePublishedDefinitionRejectsAnOversizedDefinition() {
        PlaybookVersionEntity version = new PlaybookVersionEntity();
        version.setId("ver-big");
        version.setStatus("PUBLISHED");
        version.setDefinitionJson("x".repeat(300_000));
        given(versions.findByTenantIdAndId(TENANT, "ver-big")).willReturn(Optional.of(version));

        assertThatThrownBy(() -> activity.resolvePublishedDefinition(TENANT, "ver-big"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SOAR_SUB_PLAYBOOK_DEFINITION_INVALID");
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private void givenRunLocked(SoarRunEntity run) {
        given(runs.findByTenantIdAndIdForUpdate(run.getTenantId(), RUN_ID)).willReturn(Optional.of(run));
    }

    private void givenNoPriorNodeRun() {
        given(nodeRuns.findByTenantIdAndRunIdAndNodeIdAndIterationPath(TENANT, RUN_ID, NODE_ID, ""))
                .willReturn(Optional.empty());
    }

    private static SoarRunEntity run(String status) {
        return runFor(TENANT, status);
    }

    private static SoarRunEntity runFor(String tenant, String status) {
        SoarRunEntity run = new SoarRunEntity();
        run.setId(RUN_ID);
        run.setTenantId(tenant);
        run.setStatus(status);
        run.setRequestedBy("alice");
        return run;
    }

    private static SoarConnectorEntity connector() {
        SoarConnectorEntity connector = new SoarConnectorEntity();
        connector.setId("conn-1");
        connector.setTenantId(TENANT);
        connector.setEnabled(true);
        connector.setRevision(1);
        connector.setConnectorType("endpoint");
        connector.setEndpoint("https://fw.example/release");
        connector.setConfigJson("{\"zone\":\"dmz\"}");
        connector.setSecretRefsJson("{\"auth\":\"secret://fw/token\"}");
        connector.setAllowedHostsJson("[\"fw.example\"]");
        return connector;
    }

    private static SoarV2NodeRequest nodeRequest(String tenant, String inputJson, int attemptNo) {
        return nodeRequestWithConnection(tenant, inputJson, attemptNo, "");
    }

    private static SoarV2NodeRequest nodeRequestWithConnection(String tenant, String inputJson,
                                                               int attemptNo, String connectionRef) {
        return new SoarV2NodeRequest(tenant, RUN_ID, NODE_ID, "ACTION", "endpoint/isolate-host", "",
                inputJson, "idem-9", connectionRef, Map.<String, Object>of("id", "alert-9"), attemptNo);
    }
}
