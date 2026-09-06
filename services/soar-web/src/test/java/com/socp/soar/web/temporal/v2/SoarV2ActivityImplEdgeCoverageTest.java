package com.socp.soar.web.temporal.v2;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.socp.platform.tenant.context.TenantContext;
import com.socp.soar.web.connector.ActionResult;
import com.socp.soar.web.connector.EnvironmentSecretResolver;
import com.socp.soar.web.connector.SoarConnectorRegistry;
import com.socp.soar.web.persistence.entity.SoarConnectorEntity;
import com.socp.soar.web.persistence.entity.SoarRunEntity;
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

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/** Edge coverage for the V2 Activity: degraded registry, corrupt JSON, lock fallbacks. */
@ExtendWith(MockitoExtension.class)
class SoarV2ActivityImplEdgeCoverageTest {

    private static final String TENANT = "tenant-a";
    private static final String RUN_ID = "run-1";
    private static final String NODE_ID = "node-1";

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
    private SoarArtifactRepository artifacts;

    private final ObjectMapper mapper = new ObjectMapper();
    private SoarV2ActivityImpl activity;

    @BeforeEach
    void setUp() {
        TenantContext.set(TENANT);
        activity = new SoarV2ActivityImpl(executor, runs, nodeRuns, events, approvals, attempts,
                connectors, connectorRegistry, secretResolver, manualTasks, mapper);
        activity.setArtifacts(artifacts);
        activity.setApprovalDecisions(approvalDecisions);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void compensateNodeWithoutRegistryReportsUnavailable() {
        SoarV2ActivityImpl degraded = new SoarV2ActivityImpl(executor, runs, nodeRuns, events,
                approvals, attempts, connectors, null, secretResolver, manualTasks, mapper);
        SoarV2NodeResult result = degraded.compensateNode(request("endpoint/isolate-host"),
                "endpoint/release-host");

        assertThat(result.status()).isEqualTo("FAILED");
        assertThat(result.errorCode()).isEqualTo("COMPENSATION_UNAVAILABLE");
        assertThat(result.errorMessage()).isEqualTo("connector registry is unavailable");
    }

    @Test
    void executeNodeToleratesCorruptInputAndConnectionJsonAndUnlockedAttemptLookup() {
        given(nodeRuns.findByTenantIdAndRunIdAndNodeIdAndIterationPath(
                eq(TENANT), eq(RUN_ID), eq(NODE_ID), eq(""))).willReturn(Optional.empty());
        given(attempts.findByTenantIdAndNodeRunIdOrderByAttemptNoAsc(anyString(), anyString()))
                .willReturn(null);
        given(attempts.findByTenantIdAndNodeRunIdAndAttemptNoForUpdate(anyString(), anyString(), eq(1)))
                .willReturn(null);
        given(attempts.findByTenantIdAndNodeRunIdAndAttemptNo(anyString(), anyString(), eq(1)))
                .willReturn(Optional.empty());
        given(connectors.findByTenantIdAndId(TENANT, "conn-1"))
                .willReturn(Optional.of(corruptConnector()));
        given(connectorRegistry.execute(any(com.socp.soar.web.connector.ActionRequest.class)))
                .willReturn(ActionResult.success("op-1", Map.of("k", "v"), Map.of("action", "x")));
        given(runs.findByTenantIdAndIdForUpdate(TENANT, RUN_ID)).willReturn(Optional.of(new SoarRunEntity()));
        given(events.findTopByTenantIdAndRunIdOrderBySequenceNoDesc(TENANT, RUN_ID))
                .willReturn(Optional.empty());
        given(events.findByTenantIdAndRunIdOrderBySequenceNoAsc(TENANT, RUN_ID)).willReturn(List.of());

        // inputJson is deliberately malformed: readMap must fall back to an
        // empty map instead of failing the activity.
        SoarV2NodeResult result = activity.executeNode(
                new SoarV2NodeRequest(TENANT, RUN_ID, NODE_ID, "ACTION", "http.webhook/request",
                        "", "not-json{", "idem-1", "conn-1", Map.of(), 0));

        assertThat(result.status()).isEqualTo("SUCCEEDED");
        verify(attempts).save(any(com.socp.soar.web.persistence.entity.SoarActionAttemptEntity.class));
        ArgumentCaptor<com.socp.soar.web.connector.ActionRequest> captor =
                ArgumentCaptor.forClass(com.socp.soar.web.connector.ActionRequest.class);
        verify(connectorRegistry).execute(captor.capture());
        assertThat(captor.getValue().parameters()).containsEntry("tenantId", TENANT);
    }

    @Test
    void markRunWaitingFallsBackToUnlockedApprovalLookupAndSanitizesCorruptSnapshot() {
        SoarRunEntity run = new SoarRunEntity();
        run.setStatus("RUNNING");
        run.setRequestedBy("alice");
        given(runs.findByTenantIdAndIdForUpdate(TENANT, RUN_ID)).willReturn(Optional.of(run));
        given(approvals.findByTenantIdAndApprovalKeyForUpdate(TENANT, RUN_ID + ":node:" + NODE_ID))
                .willReturn(null);
        given(approvals.findByTenantIdAndApprovalKey(TENANT, RUN_ID + ":node:" + NODE_ID))
                .willReturn(Optional.empty());
        given(events.findTopByTenantIdAndRunIdOrderBySequenceNoDesc(TENANT, RUN_ID))
                .willReturn(Optional.empty());
        given(events.findByTenantIdAndRunIdOrderBySequenceNoAsc(TENANT, RUN_ID)).willReturn(List.of());

        activity.markRunWaitingWithPolicyV2(TENANT, RUN_ID, NODE_ID, 3600, 1,
                "http.webhook/request", "hash-1", "not-json{");

        ArgumentCaptor<com.socp.soar.web.persistence.entity.SoarApprovalEntity> captor =
                ArgumentCaptor.forClass(com.socp.soar.web.persistence.entity.SoarApprovalEntity.class);
        verify(approvals).save(captor.capture());
        // redactJson fell back to "{}" and approvalPolicyJson to null for the
        // malformed snapshot instead of failing the human gate.
        assertThat(captor.getValue().getTargetSnapshotJson()).isEqualTo("{}");
        assertThat(captor.getValue().getPolicyJson()).isNull();
        assertThat(captor.getValue().getRequestedBy()).isEqualTo("alice");
    }

    @Test
    void markManualTaskWaitingFallsBackToUnlockedTaskLookup() {
        SoarRunEntity run = new SoarRunEntity();
        run.setStatus("RUNNING");
        given(runs.findByTenantIdAndIdForUpdate(TENANT, RUN_ID)).willReturn(Optional.of(run));
        given(manualTasks.findByTenantIdAndRunIdAndNodeIdForUpdate(TENANT, RUN_ID, NODE_ID))
                .willReturn(null);
        given(manualTasks.findByTenantIdAndRunIdAndNodeId(TENANT, RUN_ID, NODE_ID))
                .willReturn(Optional.empty());
        given(events.findTopByTenantIdAndRunIdOrderBySequenceNoDesc(TENANT, RUN_ID))
                .willReturn(Optional.empty());
        given(events.findByTenantIdAndRunIdOrderBySequenceNoAsc(TENANT, RUN_ID)).willReturn(List.of());

        activity.markManualTaskWaiting(TENANT, RUN_ID, NODE_ID, "{\"type\":\"object\"}",
                "analyst", "not-a-date");

        ArgumentCaptor<com.socp.soar.web.persistence.entity.SoarManualTaskEntity> captor =
                ArgumentCaptor.forClass(com.socp.soar.web.persistence.entity.SoarManualTaskEntity.class);
        verify(manualTasks).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo("PENDING");
        assertThat(captor.getValue().getAssignee()).isEqualTo("analyst");
        assertThat(captor.getValue().getDueAt()).isNotNull();
    }

    // ---- helpers ----

    private static SoarV2NodeRequest request(String actionRef) {
        return new SoarV2NodeRequest(TENANT, RUN_ID, NODE_ID, "ACTION", actionRef, "", "{}",
                "idem-1", "", Map.of(), 0);
    }

    private static SoarConnectorEntity corruptConnector() {
        SoarConnectorEntity row = new SoarConnectorEntity();
        row.setId("conn-1");
        row.setTenantId(TENANT);
        row.setName("conn");
        row.setConnectorType("HTTP.WEBHOOK");
        row.setEndpoint("https://hooks.example.test/x");
        row.setConfigJson("{}");
        row.setSecretRefsJson("not-json{");
        row.setAllowedHostsJson("not-json{");
        row.setEnabled(true);
        row.setRevision(1);
        return row;
    }
}
