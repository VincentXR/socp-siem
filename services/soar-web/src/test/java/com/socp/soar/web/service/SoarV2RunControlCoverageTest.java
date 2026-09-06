package com.socp.soar.web.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.socp.platform.tenant.context.TenantContext;
import com.socp.soar.web.definition.SoarDefinitionValidator;
import com.socp.soar.web.domain.v2.DefinitionValidationResult;
import com.socp.soar.web.domain.v2.SoarRunStatus;
import com.socp.soar.web.persistence.entity.PlaybookVersionEntity;
import com.socp.soar.web.persistence.entity.SoarApprovalEntity;
import com.socp.soar.web.persistence.entity.SoarArtifactEntity;
import com.socp.soar.web.persistence.entity.SoarDispatchOutboxEntity;
import com.socp.soar.web.persistence.entity.SoarNodeRunEntity;
import com.socp.soar.web.persistence.entity.SoarPlaybookEntity;
import com.socp.soar.web.persistence.entity.SoarRunEntity;
import com.socp.soar.web.persistence.entity.SoarRunEventEntity;
import com.socp.soar.web.persistence.entity.SoarSignalOutboxEntity;
import com.socp.soar.web.persistence.repository.PlaybookVersionRepository;
import com.socp.soar.web.persistence.repository.SoarActionAttemptRepository;
import com.socp.soar.web.persistence.repository.SoarApprovalRepository;
import com.socp.soar.web.persistence.repository.SoarArtifactRepository;
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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
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
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Run control coverage for {@link SoarV2Service}: manual admission, cancellation,
 * operational dead-letter handling and the read-side projections.
 *
 * <p>Connector registry and connection repository are deliberately {@code null}
 * so admission does not depend on connector health; every other collaborator is
 * a Mockito mock and nothing touches the network, a database or Temporal.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SoarV2RunControlCoverageTest {

    private static final String SAFE_DEFINITION = "{\"schemaVersion\":\"soar.playbook/v2\","
            + "\"entryNodeId\":\"start\",\"nodes\":["
            + "{\"id\":\"start\",\"type\":\"START\",\"name\":\"Start\"},"
            + "{\"id\":\"notify\",\"type\":\"ACTION\",\"actionRef\":\"socp.notify/send-channel\"},"
            + "{\"id\":\"end\",\"type\":\"END\",\"name\":\"End\",\"outcome\":\"SUCCEEDED\"}],"
            + "\"edges\":[{\"from\":\"start\",\"to\":\"end\"}]}";

    private static final String HIGH_RISK_DEFINITION = "{\"schemaVersion\":\"soar.playbook/v2\","
            + "\"entryNodeId\":\"start\",\"nodes\":["
            + "{\"id\":\"start\",\"type\":\"START\",\"name\":\"Start\"},"
            + "{\"id\":\"contain\",\"type\":\"ACTION\",\"actionRef\":\"endpoint/isolate-host\"},"
            + "{\"id\":\"end\",\"type\":\"END\",\"name\":\"End\",\"outcome\":\"SUCCEEDED\"}],"
            + "\"edges\":[{\"from\":\"start\",\"to\":\"end\"}]}";

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

    private final ObjectMapper mapper = new ObjectMapper();

    private SoarV2Service service;

    @BeforeEach
    void setUp() {
        TenantContext.set("tenant-a");
        service = new SoarV2Service(playbooks, versions, runs, dispatches, nodes, events, approvals,
                validator, mapper, temporal, attempts, manualTasks, signals, null, null);
        service.setArtifacts(artifacts);
        // appendEvent locks the owning run before it allocates a sequence number.
        given(runs.findByTenantIdAndIdForUpdate(eq("tenant-a"), anyString()))
                .willReturn(Optional.of(run("run-lock", "req-lock", SoarRunStatus.QUEUED)));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    // ---------------------------------------------------------------- queueManualRun

    @Test
    void queueManualRunPersistsRunAndPendingDispatch() {
        given(runs.findByTenantIdAndRequestId("tenant-a", "req-1")).willReturn(Optional.empty());
        given(versions.findByTenantIdAndId("tenant-a", "ver-1"))
                .willReturn(Optional.of(version("pb-1", "ver-1", "PUBLISHED", SAFE_DEFINITION)));
        given(playbooks.findByTenantIdAndId("tenant-a", "pb-1")).willReturn(Optional.of(playbook("pb-1", "ACTIVE")));
        given(validator.validate(anyString())).willReturn(validation(true, 0));

        Map<String, Object> queued = service.queueManualRun("req-1", "ver-1",
                Map.of("type", "alert", "id", "alert-9"), Map.of("ticket", "INC-1"));

        assertThat(queued).containsEntry("status", "QUEUED")
                .containsEntry("duplicate", false)
                .containsEntry("requestId", "req-1")
                .containsEntry("triggerType", "MANUAL");

        ArgumentCaptor<SoarRunEntity> runCaptor = ArgumentCaptor.forClass(SoarRunEntity.class);
        verify(runs).save(runCaptor.capture());
        SoarRunEntity run = runCaptor.getValue();
        assertThat(run.getTenantId()).isEqualTo("tenant-a");
        assertThat(run.getRequestId()).isEqualTo("req-1");
        assertThat(run.getExecutionSeriesId()).isEqualTo(run.getId());
        assertThat(run.getSubjectId()).isEqualTo("alert-9");
        assertThat(run.getRequestedBy()).isEqualTo("operator");
        assertThat(run.getStatus()).isEqualTo(SoarRunStatus.QUEUED.name());
        assertThat(run.getInputJson()).contains("INC-1");

        ArgumentCaptor<SoarDispatchOutboxEntity> outboxCaptor =
                ArgumentCaptor.forClass(SoarDispatchOutboxEntity.class);
        verify(dispatches).save(outboxCaptor.capture());
        assertThat(outboxCaptor.getValue().getStatus()).isEqualTo("PENDING");
        assertThat(outboxCaptor.getValue().getRunId()).isEqualTo(run.getId());
        assertThat(outboxCaptor.getValue().getTenantId()).isEqualTo("tenant-a");

        ArgumentCaptor<SoarRunEventEntity> eventCaptor = ArgumentCaptor.forClass(SoarRunEventEntity.class);
        verify(events).save(eventCaptor.capture());
        assertThat(eventCaptor.getValue().getEventType()).isEqualTo("RUN_QUEUED");
        verify(approvals, never()).save(any(SoarApprovalEntity.class));
    }

    @Test
    void duplicateRequestIdReturnsTheSameRunWithoutAdmittingAgain() {
        given(runs.findByTenantIdAndRequestId("tenant-a", "req-1"))
                .willReturn(Optional.of(run("run-1", "req-1", SoarRunStatus.QUEUED)));

        Map<String, Object> result = service.queueManualRun("req-1", "ver-1", null, null);

        assertThat(result).containsEntry("duplicate", true).containsEntry("runId", "run-1");
        verify(versions, never()).findByTenantIdAndId(anyString(), anyString());
        verify(runs, never()).save(any(SoarRunEntity.class));
        verify(dispatches, never()).save(any(SoarDispatchOutboxEntity.class));
    }

    @Test
    void unpublishedVersionIsRejected() {
        given(runs.findByTenantIdAndRequestId("tenant-a", "req-2")).willReturn(Optional.empty());
        given(versions.findByTenantIdAndId("tenant-a", "ver-2"))
                .willReturn(Optional.of(version("pb-1", "ver-2", "DRAFT", SAFE_DEFINITION)));

        assertRejected(HttpStatus.CONFLICT, "SOAR_VERSION_NOT_PUBLISHED",
                () -> service.queueManualRun("req-2", "ver-2", null, null));
    }

    @Test
    void archivedPlaybookCannotStartNewRuns() {
        given(runs.findByTenantIdAndRequestId("tenant-a", "req-3")).willReturn(Optional.empty());
        given(versions.findByTenantIdAndId("tenant-a", "ver-1"))
                .willReturn(Optional.of(version("pb-1", "ver-1", "PUBLISHED", SAFE_DEFINITION)));
        given(playbooks.findByTenantIdAndId("tenant-a", "pb-1")).willReturn(Optional.of(playbook("pb-1", "ARCHIVED")));

        assertRejected(HttpStatus.CONFLICT, "SOAR_PLAYBOOK_ARCHIVED",
                () -> service.queueManualRun("req-3", "ver-1", null, null));
    }

    @Test
    void definitionThatFailsRuntimeValidationIsRejected() {
        given(runs.findByTenantIdAndRequestId("tenant-a", "req-4")).willReturn(Optional.empty());
        given(versions.findByTenantIdAndId("tenant-a", "ver-1"))
                .willReturn(Optional.of(version("pb-1", "ver-1", "PUBLISHED", SAFE_DEFINITION)));
        given(playbooks.findByTenantIdAndId("tenant-a", "pb-1")).willReturn(Optional.of(playbook("pb-1", "ACTIVE")));
        given(validator.validate(anyString())).willReturn(validation(false, 0));

        assertRejected(HttpStatus.CONFLICT, "SOAR_DEFINITION_INVALID",
                () -> service.queueManualRun("req-4", "ver-1", null, null));
    }

    @Test
    void oversizedRunInputIsRejected() {
        given(runs.findByTenantIdAndRequestId("tenant-a", "req-5")).willReturn(Optional.empty());
        given(versions.findByTenantIdAndId("tenant-a", "ver-1"))
                .willReturn(Optional.of(version("pb-1", "ver-1", "PUBLISHED", SAFE_DEFINITION)));
        given(playbooks.findByTenantIdAndId("tenant-a", "pb-1")).willReturn(Optional.of(playbook("pb-1", "ACTIVE")));
        given(validator.validate(anyString())).willReturn(validation(true, 0));

        Map<String, Object> oversized = Map.of("blob", "x".repeat(300 * 1024));
        assertRejected(HttpStatus.PAYLOAD_TOO_LARGE, "SOAR_INPUT_TOO_LARGE",
                () -> service.queueManualRun("req-5", "ver-1", null, oversized));
    }

    @Test
    void subjectWithTooManyFieldsIsRejected() {
        given(runs.findByTenantIdAndRequestId("tenant-a", "req-6")).willReturn(Optional.empty());
        given(versions.findByTenantIdAndId("tenant-a", "ver-1"))
                .willReturn(Optional.of(version("pb-1", "ver-1", "PUBLISHED", SAFE_DEFINITION)));
        given(playbooks.findByTenantIdAndId("tenant-a", "pb-1")).willReturn(Optional.of(playbook("pb-1", "ACTIVE")));
        given(validator.validate(anyString())).willReturn(validation(true, 0));

        Map<String, Object> subject = new LinkedHashMap<>();
        for (int index = 0; index < 9; index++) subject.put("field-" + index, index);

        assertRejected(HttpStatus.PAYLOAD_TOO_LARGE, "SOAR_INPUT_INVALID",
                () -> service.queueManualRun("req-6", "ver-1", subject, null));
    }

    @Test
    void highRiskVersionWaitsForApprovalWithHoldOutbox() {
        given(runs.findByTenantIdAndRequestId("tenant-a", "req-7")).willReturn(Optional.empty());
        given(versions.findByTenantIdAndId("tenant-a", "ver-9"))
                .willReturn(Optional.of(version("pb-1", "ver-9", "PUBLISHED", HIGH_RISK_DEFINITION)));
        given(playbooks.findByTenantIdAndId("tenant-a", "pb-1")).willReturn(Optional.of(playbook("pb-1", "ACTIVE")));
        given(validator.validate(anyString())).willReturn(validation(true, 1));

        Map<String, Object> queued = service.queueManualRun("req-7", "ver-9",
                Map.of("type", "host", "id", "web-1"), null);

        assertThat(queued).containsEntry("status", SoarRunStatus.WAITING_APPROVAL.name())
                .containsEntry("duplicate", false);

        ArgumentCaptor<SoarDispatchOutboxEntity> outboxCaptor =
                ArgumentCaptor.forClass(SoarDispatchOutboxEntity.class);
        verify(dispatches).save(outboxCaptor.capture());
        assertThat(outboxCaptor.getValue().getStatus()).isEqualTo("HOLD");

        ArgumentCaptor<SoarApprovalEntity> approvalCaptor = ArgumentCaptor.forClass(SoarApprovalEntity.class);
        verify(approvals).save(approvalCaptor.capture());
        SoarApprovalEntity approval = approvalCaptor.getValue();
        assertThat(approval.getTenantId()).isEqualTo("tenant-a");
        assertThat(approval.getStatus()).isEqualTo("PENDING");
        assertThat(approval.getRunId()).isEqualTo(queued.get("runId"));
        assertThat(approval.getActionRef()).isEqualTo("endpoint/isolate-host");
        assertThat(approval.getRequestedBy()).isEqualTo("operator");
        assertThat(approval.getTargetSnapshotJson()).contains("endpoint/isolate-host");

        ArgumentCaptor<SoarRunEventEntity> eventCaptor = ArgumentCaptor.forClass(SoarRunEventEntity.class);
        verify(events).save(eventCaptor.capture());
        assertThat(eventCaptor.getValue().getEventType()).isEqualTo("RUN_WAITING_APPROVAL");
    }

    // ---------------------------------------------------------------------- rerun

    @Test
    void rerunRequiresExplicitConfirmation() {
        assertRejected(HttpStatus.CONFLICT, "SOAR_RERUN_CONFIRMATION_REQUIRED",
                () -> service.rerun("run-1", "because", false));
        verify(runs, never()).findByTenantIdAndId(anyString(), anyString());
        verify(runs, never()).save(any(SoarRunEntity.class));
    }

    // ------------------------------------------------------------------ cancelRun

    @Test
    void cancellingQueuedRunDefaultsReasonAndCancelsOutboxAndApproval() {
        SoarRunEntity run = run("run-1", "req-1", SoarRunStatus.QUEUED);
        SoarDispatchOutboxEntity outbox = outbox("d-1", "run-1", "PENDING");
        SoarApprovalEntity approval = approval("apr-1", "run-1", "PENDING");
        given(runs.findByTenantIdAndId("tenant-a", "run-1")).willReturn(Optional.of(run));
        given(dispatches.findByTenantIdAndRunId("tenant-a", "run-1")).willReturn(Optional.of(outbox));
        given(approvals.findAllByTenantIdAndRunIdOrderByCreatedAtAsc("tenant-a", "run-1"))
                .willReturn(List.of(approval));

        Map<String, Object> cancelled = service.cancelRun("run-1", null);

        assertThat(cancelled).containsEntry("status", SoarRunStatus.CANCELLED.name());
        assertThat(run.getStatus()).isEqualTo(SoarRunStatus.CANCELLED.name());
        assertThat(run.getErrorCode()).isEqualTo("SOAR_RUN_CANCELLED");
        assertThat(run.getErrorMessage()).isEqualTo("operator requested cancellation");
        assertThat(run.getCompletedAt()).isNotNull();
        assertThat(outbox.getStatus()).isEqualTo("CANCELLED");
        assertThat(approval.getStatus()).isEqualTo("CANCELLED");
        verify(events).save(any(SoarRunEventEntity.class));
    }

    @Test
    void cancellingRunningRunRequestsWorkflowCancellation() {
        SoarRunEntity run = run("run-2", "req-2", SoarRunStatus.RUNNING);
        given(runs.findByTenantIdAndId("tenant-a", "run-2")).willReturn(Optional.of(run));

        Map<String, Object> result = service.cancelRun("run-2", "duplicate alert");

        assertThat(result).containsEntry("status", SoarRunStatus.CANCELLING.name());
        assertThat(run.getStatus()).isEqualTo(SoarRunStatus.CANCELLING.name());
        assertThat(run.getErrorCode()).isEqualTo("SOAR_RUN_CANCEL_REQUESTED");
        assertThat(run.getErrorMessage()).isEqualTo("duplicate alert");
        assertThat(run.getCompletedAt()).isNull();
    }

    @Test
    void cancellingApprovalGateWithoutWorkflowIsTerminal() {
        SoarRunEntity run = run("run-3", "req-3", SoarRunStatus.WAITING_APPROVAL);
        given(runs.findByTenantIdAndId("tenant-a", "run-3")).willReturn(Optional.of(run));

        Map<String, Object> result = service.cancelRun("run-3", "no longer needed");

        assertThat(result).containsEntry("status", SoarRunStatus.CANCELLED.name());
        assertThat(run.getStatus()).isEqualTo(SoarRunStatus.CANCELLED.name());
    }

    @Test
    void terminalRunIsNotCancellable() {
        given(runs.findByTenantIdAndId("tenant-a", "run-4"))
                .willReturn(Optional.of(run("run-4", "req-4", SoarRunStatus.SUCCEEDED)));

        assertRejected(HttpStatus.CONFLICT, "SOAR_RUN_NOT_CANCELLABLE",
                () -> service.cancelRun("run-4", "too late"));
    }

    // --------------------------------------------------------- stats / dead letter

    @Test
    @SuppressWarnings("unchecked")
    void statsExposeStatusCountsAndBacklogs() {
        given(runs.countByTenantIdAndStatus(eq("tenant-a"), anyString()))
                .willAnswer(invocation -> "RUNNING".equals(invocation.getArgument(1)) ? 3L : 0L);
        given(dispatches.countByTenantIdAndStatusAndNextAttemptAtLessThanEqual(
                eq("tenant-a"), eq("PENDING"), any(Instant.class))).willReturn(7L);
        given(signals.countByTenantIdAndStatus("tenant-a", "PENDING")).willReturn(2L);

        Map<String, Object> stats = service.stats();

        assertThat(stats).containsKeys("runsByStatus", "dispatchBacklog", "signalBacklog", "generatedAt");
        Map<String, Long> byStatus = (Map<String, Long>) stats.get("runsByStatus");
        assertThat(byStatus).containsEntry("RUNNING", 3L).doesNotContainKey("FAILED");
        assertThat(stats.get("dispatchBacklog")).isEqualTo(7L);
        assertThat(stats.get("signalBacklog")).isEqualTo(2L);
    }

    @Test
    void deadDispatchesIncludeDispatchAndSignalRows() {
        given(dispatches.findByTenantIdAndStatusOrderByUpdatedAtAsc("tenant-a", "DEAD"))
                .willReturn(List.of(outbox("d-1", "run-1", "DEAD")));
        given(signals.findByTenantIdAndStatusOrderByUpdatedAtAsc("tenant-a", "DEAD"))
                .willReturn(List.of(signal("s-1", "run-2", "APPROVAL")));

        List<Map<String, Object>> dead = service.deadDispatches();

        assertThat(dead).hasSize(2);
        assertThat(dead.get(0)).containsEntry("id", "d-1")
                .containsEntry("runId", "run-1")
                .containsEntry("status", "DEAD")
                .containsEntry("attempts", 3);
        assertThat(dead.get(1)).containsEntry("id", "s-1")
                .containsEntry("kind", "SIGNAL")
                .containsEntry("signalType", "APPROVAL")
                .containsEntry("signalKey", "gate-1");
    }

    @Test
    void requeueDeadResetsDispatchAndReturnsRunToQueued() {
        SoarDispatchOutboxEntity row = outbox("d-1", "run-1", "DEAD");
        SoarRunEntity run = run("run-1", "req-1", SoarRunStatus.DEAD);
        given(dispatches.findByTenantIdAndId("tenant-a", "d-1")).willReturn(Optional.of(row));
        given(runs.findByTenantIdAndId("tenant-a", "run-1")).willReturn(Optional.of(run));

        Map<String, Object> requeued = service.requeueDead("d-1", "temporal was down");

        assertThat(requeued).containsEntry("kind", "DISPATCH").containsEntry("status", "PENDING");
        assertThat(row.getStatus()).isEqualTo("PENDING");
        assertThat(row.getAttempts()).isZero();
        assertThat(row.getLastError()).isEqualTo("requeued: temporal was down");
        assertThat(run.getStatus()).isEqualTo(SoarRunStatus.QUEUED.name());
    }

    @Test
    void requeueDeadResolvesSignalRowsThroughTheSameOperatorId() {
        SoarSignalOutboxEntity row = signal("s-1", "run-2", "APPROVAL");
        given(dispatches.findByTenantIdAndId("tenant-a", "s-1")).willReturn(Optional.empty());
        given(signals.findByTenantIdAndId("tenant-a", "s-1")).willReturn(Optional.of(row));

        Map<String, Object> requeued = service.requeueDead("s-1", "signal worker restarted");

        assertThat(requeued).containsEntry("kind", "SIGNAL")
                .containsEntry("status", "PENDING")
                .containsEntry("signalType", "APPROVAL");
        assertThat(row.getStatus()).isEqualTo("PENDING");
        assertThat(row.getAttempts()).isZero();
    }

    @Test
    void requeueDeadRejectsLiveAndMissingRows() {
        given(dispatches.findByTenantIdAndId("tenant-a", "d-live"))
                .willReturn(Optional.of(outbox("d-live", "run-1", "PENDING")));
        assertRejected(HttpStatus.CONFLICT, "SOAR_OUTBOX_NOT_DEAD",
                () -> service.requeueDead("d-live", "why"));

        given(dispatches.findByTenantIdAndId("tenant-a", "missing")).willReturn(Optional.empty());
        given(signals.findByTenantIdAndId("tenant-a", "missing")).willReturn(Optional.empty());
        assertRejected(HttpStatus.NOT_FOUND, "SOAR_OUTBOX_NOT_FOUND",
                () -> service.requeueDead("missing", "why"));
    }

    @Test
    void discardDeadRequiresAnOperatorReason() {
        assertRejected(HttpStatus.BAD_REQUEST, "SOAR_INPUT_INVALID",
                () -> service.discardDead("d-1", "   "));
        verify(dispatches, never()).findByTenantIdAndId(anyString(), anyString());
    }

    @Test
    void discardDeadSuppressesRunAndDiscardsDispatch() {
        SoarDispatchOutboxEntity row = outbox("d-1", "run-1", "DEAD");
        SoarRunEntity run = run("run-1", "req-1", SoarRunStatus.QUEUED);
        given(dispatches.findByTenantIdAndId("tenant-a", "d-1")).willReturn(Optional.of(row));
        given(runs.findByTenantIdAndIdForUpdate("tenant-a", "run-1")).willReturn(Optional.of(run));

        Map<String, Object> discarded = service.discardDead("d-1", "run is obsolete");

        assertThat(discarded).containsEntry("kind", "DISPATCH").containsEntry("status", "DISCARDED");
        assertThat(row.getStatus()).isEqualTo("DISCARDED");
        assertThat(run.getStatus()).isEqualTo(SoarRunStatus.SUPPRESSED.name());
        assertThat(run.getErrorCode()).isEqualTo("DISPATCH_DISCARDED");
        assertThat(run.getErrorMessage()).isEqualTo("run is obsolete");
        assertThat(run.getCompletedAt()).isNotNull();
    }

    @Test
    void discardDeadSignalSuppressesWaitingRun() {
        SoarSignalOutboxEntity row = signal("s-1", "run-2", "APPROVAL");
        SoarRunEntity run = run("run-2", "req-2", SoarRunStatus.WAITING_APPROVAL);
        given(dispatches.findByTenantIdAndId("tenant-a", "s-1")).willReturn(Optional.empty());
        given(signals.findByTenantIdAndId("tenant-a", "s-1")).willReturn(Optional.of(row));
        given(runs.findByTenantIdAndIdForUpdate("tenant-a", "run-2")).willReturn(Optional.of(run));

        Map<String, Object> discarded = service.discardDead("s-1", "approver left");

        assertThat(discarded).containsEntry("kind", "SIGNAL").containsEntry("status", "DISCARDED");
        assertThat(row.getStatus()).isEqualTo("DISCARDED");
        assertThat(run.getStatus()).isEqualTo(SoarRunStatus.SUPPRESSED.name());
        assertThat(run.getErrorCode()).isEqualTo("SIGNAL_DISCARDED");
        verify(events).save(any(SoarRunEventEntity.class));
    }

    // ------------------------------------------------------------- read projections

    @Test
    void listRunsUsesTenantScopedQueriesAndNormalizesFilters() {
        Pageable pageable = PageRequest.of(0, 10);
        SoarRunEntity run = run("run-1", "req-1", SoarRunStatus.FAILED);
        given(runs.findByTenantIdOrderByCreatedAtDesc("tenant-a", pageable))
                .willReturn(new PageImpl<>(List.of(run)));
        given(runs.searchByTenant(eq("tenant-a"), eq("FAILED"), isNull(), isNull(), isNull(),
                any(), any(), any())).willReturn(new PageImpl<>(List.of(run)));

        Page<Map<String, Object>> all = service.listRuns(pageable);
        assertThat(all.getContent()).hasSize(1);
        assertThat(all.getContent().get(0)).containsEntry("runId", "run-1");

        Page<Map<String, Object>> searched = service.listRuns(pageable, "FAILED", "  ", null, "", null, null);
        assertThat(searched.getContent()).hasSize(1);
        assertThat(searched.getContent().get(0)).containsEntry("status", "FAILED");
    }

    @Test
    void getRunProjectsRunOrRejectsForeignTenantRows() {
        given(runs.findByTenantIdAndId("tenant-a", "run-1"))
                .willReturn(Optional.of(run("run-1", "req-1", SoarRunStatus.RUNNING)));

        Map<String, Object> view = service.getRun("run-1");
        assertThat(view).containsEntry("runId", "run-1").containsEntry("status", "RUNNING");

        given(runs.findByTenantIdAndId("tenant-a", "run-foreign")).willReturn(Optional.empty());
        assertRejected(HttpStatus.NOT_FOUND, "SOAR_RUN_NOT_FOUND", () -> service.getRun("run-foreign"));
    }

    @Test
    void listNodesEventsAndAttemptsStayTenantScoped() {
        SoarRunEntity run = run("run-1", "req-1", SoarRunStatus.RUNNING);
        SoarNodeRunEntity node = node("node-1", "run-1", "contain", "FAILED");
        Pageable pageable = PageRequest.of(0, 10);
        given(runs.findByTenantIdAndId("tenant-a", "run-1")).willReturn(Optional.of(run));
        given(nodes.findByTenantIdAndRunIdOrderByUpdatedAtAsc("tenant-a", "run-1")).willReturn(List.of(node));
        given(nodes.findByTenantIdAndRunIdOrderByUpdatedAtAsc("tenant-a", "run-1", pageable))
                .willReturn(new PageImpl<>(List.of(node)));
        given(events.findByTenantIdAndRunIdOrderBySequenceNoAsc("tenant-a", "run-1"))
                .willReturn(List.of(event("e-1", "run-1", 1L, "RUN_QUEUED")));
        given(events.findByTenantIdAndRunIdAndSequenceNoGreaterThanOrderBySequenceNoAsc(
                eq("tenant-a"), eq("run-1"), eq(0L), any(Pageable.class)))
                .willReturn(new PageImpl<>(List.of(event("e-2", "run-1", 2L, "NODE_FAILED"))));
        given(nodes.findByTenantIdAndIdForUpdate("tenant-a", "node-1")).willReturn(Optional.of(node));
        given(attempts.findByTenantIdAndNodeRunIdOrderByAttemptNoAsc("tenant-a", "node-1", pageable))
                .willReturn(new PageImpl<>(List.of(attempt("a-1", "node-1"))));

        assertThat(service.listNodes("run-1")).hasSize(1);
        assertThat(service.listNodes("run-1").get(0)).containsEntry("nodeId", "contain");
        assertThat(service.listNodes("run-1", pageable).getContent()).hasSize(1);
        assertThat(service.listEvents("run-1")).hasSize(1);
        assertThat(service.listEvents("run-1").get(0)).containsEntry("eventType", "RUN_QUEUED");
        assertThat(service.listEvents("run-1", -5L, pageable).getContent())
                .extracting(entry -> entry.get("eventType")).containsExactly("NODE_FAILED");
        assertThat(service.listNodeAttempts("node-1", pageable).getContent())
                .extracting(entry -> entry.get("id")).containsExactly("a-1");

        given(runs.findByTenantIdAndId("tenant-a", "run-x")).willReturn(Optional.empty());
        assertRejected(HttpStatus.NOT_FOUND, "SOAR_RUN_NOT_FOUND", () -> service.listNodes("run-x"));
    }

    // -------------------------------------------------------------------- artifacts

    @Test
    void artifactsAreListedAndServedOnlyForTheOwningTenant() {
        SoarRunEntity run = run("run-1", "req-1", SoarRunStatus.SUCCEEDED);
        SoarArtifactEntity artifact = artifact("art-1", "{\"host\":\"web-1\"}");
        given(runs.findByTenantIdAndId("tenant-a", "run-1")).willReturn(Optional.of(run));
        given(artifacts.findByTenantIdAndRunIdOrderByCreatedAtAsc("tenant-a", "run-1"))
                .willReturn(List.of(artifact));
        given(artifacts.findByTenantIdAndRunIdOrderByCreatedAtAsc(eq("tenant-a"), eq("run-1"), any(Pageable.class)))
                .willReturn(new PageImpl<>(List.of(artifact)));
        given(artifacts.findByTenantIdAndId("tenant-a", "art-1")).willReturn(Optional.of(artifact));

        assertThat(service.listArtifacts("run-1")).hasSize(1);
        assertThat(service.listArtifacts("run-1").get(0)).containsEntry("id", "art-1");
        assertThat(service.listArtifacts("run-1", PageRequest.of(0, 5)).getContent()).hasSize(1);
        assertThat(service.getArtifact("art-1")).containsEntry("classification", "INTERNAL");
        assertThat(service.getArtifactContent("art-1")).isEqualTo("{\"host\":\"web-1\"}");
    }

    @Test
    void artifactContentIsUnavailableWithoutInlineStorage() {
        SoarArtifactEntity artifact = artifact("art-2", null);
        given(artifacts.findByTenantIdAndId("tenant-a", "art-2")).willReturn(Optional.of(artifact));

        assertRejected(HttpStatus.SERVICE_UNAVAILABLE, "SOAR_ARTIFACT_CONTENT_UNAVAILABLE",
                () -> service.getArtifactContent("art-2"));
    }

    @Test
    void uploadArtifactStoresBoundedInlineEvidence() {
        SoarRunEntity run = run("run-1", "req-1", SoarRunStatus.RUNNING);
        given(runs.findByTenantIdAndId("tenant-a", "run-1")).willReturn(Optional.of(run));
        given(runs.findByTenantIdAndIdForUpdate("tenant-a", "run-1")).willReturn(Optional.of(run));
        given(artifacts.save(any(SoarArtifactEntity.class)))
                .willAnswer(invocation -> invocation.getArgument(0));
        ObjectNode content = mapper.createObjectNode();
        content.put("note", "analyst evidence");

        Map<String, Object> uploaded = service.uploadArtifact("run-1", null, "application/json",
                "internal", content);

        assertThat(uploaded).containsEntry("mediaType", "application/json")
                .containsEntry("classification", "INTERNAL")
                .containsEntry("runId", "run-1");
        assertThat(((Number) uploaded.get("sizeBytes")).longValue()).isPositive();
        assertThat(String.valueOf(uploaded.get("storageRef"))).startsWith("db://soar-artifacts/");
        ArgumentCaptor<SoarArtifactEntity> captor = ArgumentCaptor.forClass(SoarArtifactEntity.class);
        verify(artifacts).save(captor.capture());
        assertThat(captor.getValue().getInlineJson()).contains("analyst evidence");
        assertThat(captor.getValue().getTenantId()).isEqualTo("tenant-a");
        verify(events).save(any(SoarRunEventEntity.class));
    }

    @Test
    void uploadArtifactRejectsInvalidMediaTypeAndClassification() {
        SoarRunEntity run = run("run-1", "req-1", SoarRunStatus.RUNNING);
        given(runs.findByTenantIdAndId("tenant-a", "run-1")).willReturn(Optional.of(run));
        ObjectNode content = mapper.createObjectNode();

        assertRejected(HttpStatus.BAD_REQUEST, "SOAR_ARTIFACT_INVALID",
                () -> service.uploadArtifact("run-1", null, "not-a-media-type", null, content));
        assertRejected(HttpStatus.BAD_REQUEST, "SOAR_ARTIFACT_INVALID",
                () -> service.uploadArtifact("run-1", null, "application/json", "TOP_SECRET", content));
        verify(artifacts, never()).save(any(SoarArtifactEntity.class));
    }

    // --------------------------------------------------------------------- helpers

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

    private static SoarRunEntity run(String id, String requestId, SoarRunStatus status) {
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
        run.setStatus(status.name());
        run.setRequestedBy("alice");
        run.setInputJson("{\"subject\":{\"type\":\"alert\"},\"inputs\":{}}");
        run.setCreatedAt(Instant.now());
        run.setUpdatedAt(Instant.now());
        return run;
    }

    private static PlaybookVersionEntity version(String playbookId, String id, String status, String definition) {
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
        playbook.setCreatedAt(Instant.now());
        playbook.setUpdatedAt(Instant.now());
        return playbook;
    }

    private static SoarApprovalEntity approval(String id, String runId, String status) {
        SoarApprovalEntity approval = new SoarApprovalEntity();
        approval.setId(id);
        approval.setTenantId("tenant-a");
        approval.setRunId(runId);
        approval.setApprovalKey(runId);
        approval.setStatus(status);
        approval.setRequestedBy("alice");
        approval.setCreatedAt(Instant.now());
        approval.setExpiresAt(Instant.now().plusSeconds(3600));
        return approval;
    }

    private static SoarDispatchOutboxEntity outbox(String id, String runId, String status) {
        SoarDispatchOutboxEntity outbox = new SoarDispatchOutboxEntity();
        outbox.setId(id);
        outbox.setTenantId("tenant-a");
        outbox.setRunId(runId);
        outbox.setStatus(status);
        outbox.setAttempts(3);
        outbox.setLastError("boom");
        outbox.setNextAttemptAt(Instant.now());
        outbox.setCreatedAt(Instant.now());
        outbox.setUpdatedAt(Instant.now());
        return outbox;
    }

    private static SoarSignalOutboxEntity signal(String id, String runId, String type) {
        SoarSignalOutboxEntity signal = new SoarSignalOutboxEntity();
        signal.setId(id);
        signal.setTenantId("tenant-a");
        signal.setRunId(runId);
        signal.setSignalType(type);
        signal.setSignalKey("gate-1");
        signal.setPayloadJson("{}");
        signal.setStatus("DEAD");
        signal.setAttempts(2);
        signal.setNextAttemptAt(Instant.now());
        signal.setCreatedAt(Instant.now());
        signal.setUpdatedAt(Instant.now());
        return signal;
    }

    private static SoarNodeRunEntity node(String id, String runId, String nodeId, String status) {
        SoarNodeRunEntity node = new SoarNodeRunEntity();
        node.setId(id);
        node.setTenantId("tenant-a");
        node.setRunId(runId);
        node.setNodeId(nodeId);
        node.setIterationPath("/");
        node.setNodeType("ACTION");
        node.setStatus(status);
        node.setInputJson("{}");
        node.setOutputJson("{}");
        node.setUpdatedAt(Instant.now());
        return node;
    }

    private static SoarRunEventEntity event(String id, String runId, long sequence, String type) {
        SoarRunEventEntity event = new SoarRunEventEntity();
        event.setId(id);
        event.setTenantId("tenant-a");
        event.setRunId(runId);
        event.setSequenceNo(sequence);
        event.setEventType(type);
        event.setActor("operator");
        event.setSummary("event " + type);
        event.setDetailJson("{}");
        event.setCreatedAt(Instant.now());
        return event;
    }

    private static com.socp.soar.web.persistence.entity.SoarActionAttemptEntity attempt(String id, String nodeRunId) {
        com.socp.soar.web.persistence.entity.SoarActionAttemptEntity attempt =
                new com.socp.soar.web.persistence.entity.SoarActionAttemptEntity();
        attempt.setId(id);
        attempt.setTenantId("tenant-a");
        attempt.setNodeRunId(nodeRunId);
        attempt.setAttemptNo(1);
        attempt.setStatus("FAILED");
        attempt.setCreatedAt(Instant.now());
        return attempt;
    }

    private static SoarArtifactEntity artifact(String id, String inlineJson) {
        SoarArtifactEntity artifact = new SoarArtifactEntity();
        artifact.setId(id);
        artifact.setTenantId("tenant-a");
        artifact.setRunId("run-1");
        artifact.setMediaType("application/json");
        artifact.setSizeBytes(16L);
        artifact.setSha256("sha-1");
        artifact.setStorageRef("db://soar-artifacts/" + id);
        artifact.setClassification("INTERNAL");
        artifact.setInlineJson(inlineJson);
        artifact.setCreatedAt(Instant.now());
        artifact.setExpiresAt(Instant.now().plusSeconds(3600));
        return artifact;
    }
}
