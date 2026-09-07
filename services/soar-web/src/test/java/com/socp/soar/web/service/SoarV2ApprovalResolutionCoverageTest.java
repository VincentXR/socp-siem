package com.socp.soar.web.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.socp.platform.tenant.context.TenantContext;
import com.socp.soar.web.domain.v2.SoarRunStatus;
import com.socp.soar.web.persistence.entity.SoarApprovalEntity;
import com.socp.soar.web.persistence.entity.SoarDispatchOutboxEntity;
import com.socp.soar.web.persistence.entity.SoarManualTaskEntity;
import com.socp.soar.web.persistence.entity.SoarNodeRunEntity;
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
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
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
 * Approval gate, unknown-result resolution and manual-task coverage for
 * {@link SoarV2Service}: decideApproval success/self-approval/expiry paths,
 * resolveUnknown guard rails and workflow resume signalling, plus manual task
 * listing and schema-validated completion. Every collaborator is a Mockito
 * mock; nothing touches Temporal, the network or a database.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SoarV2ApprovalResolutionCoverageTest {

    private static final String FORM_SCHEMA = "{\"type\":\"object\","
            + "\"required\":[\"confirmed\"],"
            + "\"properties\":{\"confirmed\":{\"type\":\"boolean\"}}}";

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
    private com.socp.soar.web.definition.SoarDefinitionValidator validator;
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
        SoarTestIdentity.setOperator();
        service = new SoarV2Service(playbooks, versions, runs, dispatches, nodes, events, approvals,
                validator, mapper, temporal, attempts, manualTasks, signals, null, null);
        service.setArtifacts(artifacts);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        SoarTestIdentity.clear();
    }

    // ------------------------------------------------------------------ approvals

    @Test
    void decideApprovalGrantsAPreDispatchGateAndReleasesTheOutbox() {
        SoarRunEntity run = run("run-1", SoarRunStatus.WAITING_APPROVAL, "bob");
        SoarApprovalEntity approval = approval("apr-1", "run-1", "PENDING");
        SoarDispatchOutboxEntity outbox = outbox("d-1", "run-1", "HOLD");
        given(approvals.findByTenantIdAndIdForUpdate("tenant-a", "apr-1"))
                .willReturn(Optional.of(approval));
        given(runs.findByTenantIdAndIdForUpdate("tenant-a", "run-1")).willReturn(Optional.of(run));
        given(dispatches.findByTenantIdAndRunId("tenant-a", "run-1")).willReturn(Optional.of(outbox));

        Map<String, Object> decided = service.decideApproval("apr-1", true, "verified with the analyst");

        assertThat(decided).containsEntry("id", "apr-1").containsEntry("status", "APPROVED");
        assertThat(approval.getStatus()).isEqualTo("APPROVED");
        assertThat(approval.getApprover()).isEqualTo("operator");
        assertThat(approval.getDecidedAt()).isNotNull();
        assertThat(run.getStatus()).isEqualTo(SoarRunStatus.QUEUED.name());
        assertThat(outbox.getStatus()).isEqualTo("PENDING");
        verify(dispatches).save(outbox);
        verify(runs).save(run);
        ArgumentCaptor<SoarRunEventEntity> eventCaptor = ArgumentCaptor.forClass(SoarRunEventEntity.class);
        verify(events).save(eventCaptor.capture());
        assertThat(eventCaptor.getValue().getEventType()).isEqualTo("APPROVAL_GRANTED");
        verify(signals, never()).save(any(SoarSignalOutboxEntity.class));
    }

    @Test
    void decideApprovalRejectingAGateSuppressesTheRunAndCancelsTheOutbox() {
        SoarRunEntity run = run("run-1", SoarRunStatus.WAITING_APPROVAL, "bob");
        SoarApprovalEntity approval = approval("apr-1", "run-1", "PENDING");
        SoarDispatchOutboxEntity outbox = outbox("d-1", "run-1", "HOLD");
        given(approvals.findByTenantIdAndIdForUpdate("tenant-a", "apr-1"))
                .willReturn(Optional.of(approval));
        given(runs.findByTenantIdAndIdForUpdate("tenant-a", "run-1")).willReturn(Optional.of(run));
        given(dispatches.findByTenantIdAndRunId("tenant-a", "run-1")).willReturn(Optional.of(outbox));

        Map<String, Object> decided = service.decideApproval("apr-1", false, "not warranted");

        assertThat(decided).containsEntry("status", "REJECTED");
        assertThat(approval.getStatus()).isEqualTo("REJECTED");
        assertThat(run.getStatus()).isEqualTo(SoarRunStatus.SUPPRESSED.name());
        assertThat(run.getCompletedAt()).isNotNull();
        assertThat(outbox.getStatus()).isEqualTo("CANCELLED");
        ArgumentCaptor<SoarRunEventEntity> eventCaptor = ArgumentCaptor.forClass(SoarRunEventEntity.class);
        verify(events).save(eventCaptor.capture());
        assertThat(eventCaptor.getValue().getEventType()).isEqualTo("APPROVAL_REJECTED");
    }

    @Test
    void decideApprovalDeniesTheRunRequesterApprovingTheirOwnGate() {
        SoarRunEntity run = run("run-1", SoarRunStatus.WAITING_APPROVAL, "operator");
        SoarApprovalEntity approval = approval("apr-1", "run-1", "PENDING");
        given(approvals.findByTenantIdAndIdForUpdate("tenant-a", "apr-1"))
                .willReturn(Optional.of(approval));
        given(runs.findByTenantIdAndIdForUpdate("tenant-a", "run-1")).willReturn(Optional.of(run));

        assertRejected(HttpStatus.FORBIDDEN, "SOAR_SELF_APPROVAL_DENIED",
                () -> service.decideApproval("apr-1", true, "self sign-off"));
        verify(approvals, never()).save(any(SoarApprovalEntity.class));
        verify(dispatches, never()).save(any(SoarDispatchOutboxEntity.class));
    }

    @Test
    void decideApprovalExpiresAnOverdueGateBeforeDeciding() {
        SoarRunEntity run = run("run-1", SoarRunStatus.WAITING_APPROVAL, "bob");
        SoarApprovalEntity approval = approval("apr-1", "run-1", "PENDING");
        approval.setExpiresAt(Instant.now().minusSeconds(60));
        SoarDispatchOutboxEntity outbox = outbox("d-1", "run-1", "HOLD");
        given(approvals.findByTenantIdAndIdForUpdate("tenant-a", "apr-1"))
                .willReturn(Optional.of(approval));
        given(runs.findByTenantIdAndIdForUpdate("tenant-a", "run-1")).willReturn(Optional.of(run));
        given(dispatches.findByTenantIdAndRunId("tenant-a", "run-1")).willReturn(Optional.of(outbox));

        assertRejected(HttpStatus.CONFLICT, "SOAR_APPROVAL_EXPIRED",
                () -> service.decideApproval("apr-1", true, "too late"));
        assertThat(approval.getStatus()).isEqualTo("EXPIRED");
        assertThat(run.getStatus()).isEqualTo(SoarRunStatus.SUPPRESSED.name());
        assertThat(run.getErrorCode()).isEqualTo("APPROVAL_EXPIRED");
        assertThat(outbox.getStatus()).isEqualTo("CANCELLED");
        verify(approvals).save(approval);
    }

    @Test
    void decideApprovalRejectsAlreadyDecidedAndUnknownGates() {
        SoarApprovalEntity decided = approval("apr-1", "run-1", "APPROVED");
        given(approvals.findByTenantIdAndIdForUpdate("tenant-a", "apr-1"))
                .willReturn(Optional.of(decided));
        given(runs.findByTenantIdAndIdForUpdate("tenant-a", "run-1"))
                .willReturn(Optional.of(run("run-1", SoarRunStatus.WAITING_APPROVAL, "bob")));
        assertRejected(HttpStatus.CONFLICT, "SOAR_APPROVAL_ALREADY_DECIDED",
                () -> service.decideApproval("apr-1", true, "again"));

        given(approvals.findByTenantIdAndIdForUpdate("tenant-a", "missing")).willReturn(Optional.empty());
        given(approvals.findByTenantIdAndId("tenant-a", "missing")).willReturn(Optional.empty());
        assertRejected(HttpStatus.NOT_FOUND, "SOAR_APPROVAL_NOT_FOUND",
                () -> service.decideApproval("missing", true, "reason"));
    }

    @Test
    void decideApprovalRequiresADecisionReason() {
        SoarRunEntity run = run("run-1", SoarRunStatus.WAITING_APPROVAL, "bob");
        SoarApprovalEntity approval = approval("apr-1", "run-1", "PENDING");
        given(approvals.findByTenantIdAndIdForUpdate("tenant-a", "apr-1"))
                .willReturn(Optional.of(approval));
        given(runs.findByTenantIdAndIdForUpdate("tenant-a", "run-1")).willReturn(Optional.of(run));

        assertRejected(HttpStatus.BAD_REQUEST, "SOAR_INPUT_INVALID",
                () -> service.decideApproval("apr-1", true, "   "));
        verify(approvals, never()).save(any(SoarApprovalEntity.class));
    }

    @Test
    void listApprovalsProjectsTenantScopedRows() {
        given(approvals.findByTenantIdOrderByCreatedAtDesc("tenant-a"))
                .willReturn(List.of(approval("apr-1", "run-1", "PENDING")));
        given(approvals.findByTenantIdOrderByCreatedAtDesc(eq("tenant-a"), any(PageRequest.class)))
                .willReturn(new PageImpl<>(List.of(approval("apr-2", "run-2", "APPROVED"))));

        List<Map<String, Object>> all = service.listApprovals();
        assertThat(all).hasSize(1);
        assertThat(all.get(0)).containsEntry("id", "apr-1")
                .containsEntry("status", "PENDING")
                .containsEntry("requestedBy", "workflow");

        Page<Map<String, Object>> paged = service.listApprovals(PageRequest.of(0, 10));
        assertThat(paged.getContent()).hasSize(1);
        assertThat(paged.getContent().get(0)).containsEntry("id", "apr-2");
    }

    // -------------------------------------------------------------- resolveUnknown

    @Test
    void resolveUnknownConfirmsNotExecutedAndRequeuesTheDispatch() {
        SoarNodeRunEntity node = node("node-1", "run-1", "contain", "ACTION_UNKNOWN");
        SoarRunEntity run = run("run-1", SoarRunStatus.ACTION_UNKNOWN, "bob");
        SoarDispatchOutboxEntity outbox = outbox("d-1", "run-1", "HOLD");
        given(nodes.findByTenantIdAndIdForUpdate("tenant-a", "node-1")).willReturn(Optional.of(node));
        given(runs.findByTenantIdAndIdForUpdate("tenant-a", "run-1")).willReturn(Optional.of(run));
        given(dispatches.findByTenantIdAndRunId("tenant-a", "run-1")).willReturn(Optional.of(outbox));

        Map<String, Object> view = service.resolveUnknown("node-1", "confirmed_not_executed",
                "firewall session log shows no block", "operator verified on call");

        assertThat(view).containsEntry("id", "node-1")
                .containsEntry("status", "CONFIRMED_NOT_EXECUTED");
        assertThat(node.getErrorCode()).isNull();
        assertThat(node.getOutputJson()).contains("CONFIRMED_NOT_EXECUTED")
                .contains("firewall session log");
        verify(nodes).save(node);
        assertThat(run.getStatus()).isEqualTo(SoarRunStatus.QUEUED.name());
        assertThat(run.getErrorCode()).isNull();
        verify(runs).save(run);
        assertThat(outbox.getStatus()).isEqualTo("PENDING");
        verify(dispatches).save(outbox);
        ArgumentCaptor<SoarRunEventEntity> eventCaptor = ArgumentCaptor.forClass(SoarRunEventEntity.class);
        verify(events).save(eventCaptor.capture());
        assertThat(eventCaptor.getValue().getEventType()).isEqualTo("ACTION_UNKNOWN_RESOLVED");
        verify(signals, never()).save(any(SoarSignalOutboxEntity.class));
    }

    @Test
    void resolveUnknownResumesAnAttachedWorkflowThroughASignal() {
        SoarNodeRunEntity node = node("node-1", "run-1", "contain", "ACTION_UNKNOWN");
        SoarRunEntity run = run("run-1", SoarRunStatus.ACTION_UNKNOWN, "bob");
        run.setTemporalWorkflowId("wf-1");
        given(nodes.findByTenantIdAndIdForUpdate("tenant-a", "node-1")).willReturn(Optional.of(node));
        given(runs.findByTenantIdAndIdForUpdate("tenant-a", "run-1")).willReturn(Optional.of(run));

        Map<String, Object> view = service.resolveUnknown("node-1", "CONFIRMED_SUCCEEDED",
                "vm console shows host still isolated", "confirmed by responder");

        assertThat(view).containsEntry("status", "CONFIRMED_SUCCEEDED");
        assertThat(run.getStatus()).isEqualTo(SoarRunStatus.RUNNING.name());
        verify(runs).save(run);
        verify(dispatches, never()).save(any(SoarDispatchOutboxEntity.class));
        ArgumentCaptor<SoarSignalOutboxEntity> signalCaptor =
                ArgumentCaptor.forClass(SoarSignalOutboxEntity.class);
        verify(signals).save(signalCaptor.capture());
        SoarSignalOutboxEntity signal = signalCaptor.getValue();
        assertThat(signal.getSignalType()).isEqualTo("UNKNOWN_RESOLUTION");
        assertThat(signal.getSignalKey()).isEqualTo("contain");
        assertThat(signal.getStatus()).isEqualTo("PENDING");
        assertThat(signal.getPayloadJson()).contains("CONFIRMED_SUCCEEDED");
    }

    @Test
    void resolveUnknownRejectsInvalidResolutionsMissingEvidenceAndMissingReason() {
        SoarNodeRunEntity node = node("node-1", "run-1", "contain", "ACTION_UNKNOWN");
        SoarRunEntity run = run("run-1", SoarRunStatus.ACTION_UNKNOWN, "bob");
        given(nodes.findByTenantIdAndIdForUpdate("tenant-a", "node-1")).willReturn(Optional.of(node));
        given(runs.findByTenantIdAndIdForUpdate("tenant-a", "run-1")).willReturn(Optional.of(run));

        assertRejected(HttpStatus.BAD_REQUEST, "SOAR_ACTION_RESULT_UNKNOWN",
                () -> service.resolveUnknown("node-1", "MAYBE", "evidence", "reason"));
        assertRejected(HttpStatus.BAD_REQUEST, "SOAR_ACTION_RESULT_UNKNOWN",
                () -> service.resolveUnknown("node-1", "CONFIRMED_SUCCEEDED", "   ", "reason"));
        assertRejected(HttpStatus.BAD_REQUEST, "SOAR_ACTION_RESULT_UNKNOWN",
                () -> service.resolveUnknown("node-1", "CONFIRMED_SUCCEEDED", "evidence", null));
        verify(nodes, never()).save(any(SoarNodeRunEntity.class));
    }

    @Test
    void resolveUnknownRejectsNodesThatAreNotUnknown() {
        given(nodes.findByTenantIdAndIdForUpdate("tenant-a", "node-1"))
                .willReturn(Optional.of(node("node-1", "run-1", "contain", "FAILED")));

        assertRejected(HttpStatus.CONFLICT, "SOAR_ACTION_RESULT_NOT_UNKNOWN",
                () -> service.resolveUnknown("node-1", "CONFIRMED_SUCCEEDED", "evidence", "reason"));
        verify(runs, never()).save(any(SoarRunEntity.class));
    }

    @Test
    void resolveUnknownRejectsMissingNodeRuns() {
        given(nodes.findByTenantIdAndIdForUpdate("tenant-a", "missing")).willReturn(Optional.empty());
        given(nodes.findByTenantIdAndId("tenant-a", "missing")).willReturn(Optional.empty());

        assertRejected(HttpStatus.NOT_FOUND, "SOAR_NODE_RUN_NOT_FOUND",
                () -> service.resolveUnknown("missing", "CONFIRMED_SUCCEEDED", "evidence", "reason"));
    }

    // ---------------------------------------------------------------- manual tasks

    @Test
    void listManualTasksProjectsPendingAndCompleteListings() {
        given(manualTasks.findByTenantIdAndStatusOrderByDueAtAsc("tenant-a", "PENDING"))
                .willReturn(List.of(manualTask("task-1", "PENDING")));
        given(manualTasks.findByTenantIdOrderByCreatedAtDesc("tenant-a"))
                .willReturn(List.of(manualTask("task-1", "PENDING"),
                        manualTask("task-2", "COMPLETED")));
        given(manualTasks.findByTenantIdOrderByCreatedAtDesc(eq("tenant-a"), any(PageRequest.class)))
                .willReturn(new PageImpl<>(List.of(manualTask("task-3", "PENDING"))));

        List<Map<String, Object>> pending = service.listManualTasks(true);
        assertThat(pending).hasSize(1);
        assertThat(pending.get(0)).containsEntry("id", "task-1")
                .containsEntry("status", "PENDING")
                .containsEntry("nodeId", "ack");

        List<Map<String, Object>> all = service.listManualTasks(false);
        assertThat(all).extracting(entry -> entry.get("id"))
                .containsExactly("task-1", "task-2");

        Page<Map<String, Object>> paged = service.listManualTasks(false, PageRequest.of(0, 10));
        assertThat(paged.getContent()).extracting(entry -> entry.get("id")).containsExactly("task-3");
    }

    @Test
    void completeManualTaskValidatesTheFormSchemaThenCompletesAndSignalsTheWorkflow() {
        SoarManualTaskEntity task = manualTask("task-1", "PENDING");
        SoarRunEntity run = run("run-9", SoarRunStatus.WAITING_INPUT, "bob");
        given(manualTasks.findByTenantIdAndIdForUpdate("tenant-a", "task-1"))
                .willReturn(Optional.empty());
        given(manualTasks.findByTenantIdAndId("tenant-a", "task-1")).willReturn(Optional.of(task));
        given(runs.findByTenantIdAndId("tenant-a", "run-9")).willReturn(Optional.of(run));
        // appendEvent locks the owning run before allocating a sequence number.
        given(runs.findByTenantIdAndIdForUpdate("tenant-a", "run-9")).willReturn(Optional.of(run));

        Map<String, Object> view = service.completeManualTask("task-1", Map.of("confirmed", true));

        assertThat(view).containsEntry("id", "task-1").containsEntry("status", "COMPLETED");
        assertThat(task.getStatus()).isEqualTo("COMPLETED");
        assertThat(task.getCompletedBy()).isEqualTo("operator");
        assertThat(task.getCompletedAt()).isNotNull();
        assertThat(task.getInputJson()).contains("true");
        verify(manualTasks).save(task);
        assertThat(run.getStatus()).isEqualTo(SoarRunStatus.QUEUED.name());
        verify(runs).save(run);
        ArgumentCaptor<SoarSignalOutboxEntity> signalCaptor =
                ArgumentCaptor.forClass(SoarSignalOutboxEntity.class);
        verify(signals).save(signalCaptor.capture());
        assertThat(signalCaptor.getValue().getSignalType()).isEqualTo("MANUAL_TASK");
        assertThat(signalCaptor.getValue().getSignalKey()).isEqualTo("ack");
        ArgumentCaptor<SoarRunEventEntity> eventCaptor = ArgumentCaptor.forClass(SoarRunEventEntity.class);
        verify(events).save(eventCaptor.capture());
        assertThat(eventCaptor.getValue().getEventType()).isEqualTo("MANUAL_TASK_COMPLETED");
    }

    @Test
    void completeManualTaskRejectsInputThatViolatesTheFormSchema() {
        SoarManualTaskEntity task = manualTask("task-1", "PENDING");
        given(manualTasks.findByTenantIdAndIdForUpdate("tenant-a", "task-1"))
                .willReturn(Optional.empty());
        given(manualTasks.findByTenantIdAndId("tenant-a", "task-1")).willReturn(Optional.of(task));

        assertRejected(HttpStatus.BAD_REQUEST, "SOAR_MANUAL_INPUT_INVALID",
                () -> service.completeManualTask("task-1", Map.of("confirmed", "yes")));
        assertRejected(HttpStatus.BAD_REQUEST, "SOAR_MANUAL_INPUT_INVALID",
                () -> service.completeManualTask("task-1", Map.of()));
        assertRejected(HttpStatus.BAD_REQUEST, "SOAR_MANUAL_INPUT_INVALID",
                () -> service.completeManualTask("task-1", null));
        verify(manualTasks, never()).save(any(SoarManualTaskEntity.class));
        verify(runs, never()).findByTenantIdAndId(anyString(), anyString());
    }

    @Test
    void completeManualTaskRejectsMissingAndAlreadyCompletedTasks() {
        given(manualTasks.findByTenantIdAndIdForUpdate("tenant-a", "missing"))
                .willReturn(Optional.empty());
        given(manualTasks.findByTenantIdAndId("tenant-a", "missing")).willReturn(Optional.empty());
        assertRejected(HttpStatus.NOT_FOUND, "SOAR_MANUAL_TASK_NOT_FOUND",
                () -> service.completeManualTask("missing", Map.of("confirmed", true)));

        given(manualTasks.findByTenantIdAndIdForUpdate("tenant-a", "task-2"))
                .willReturn(Optional.empty());
        given(manualTasks.findByTenantIdAndId("tenant-a", "task-2"))
                .willReturn(Optional.of(manualTask("task-2", "COMPLETED")));
        assertRejected(HttpStatus.CONFLICT, "SOAR_MANUAL_TASK_ALREADY_COMPLETED",
                () -> service.completeManualTask("task-2", Map.of("confirmed", true)));
    }

    @Test
    void completeManualTaskCannotResurrectAPartiallySucceededRun() {
        SoarManualTaskEntity task = manualTask("task-terminal", "PENDING");
        task.setRunId("run-terminal");
        SoarRunEntity run = run("run-terminal", SoarRunStatus.PARTIALLY_SUCCEEDED, "bob");
        given(manualTasks.findByTenantIdAndIdForUpdate("tenant-a", "task-terminal"))
                .willReturn(Optional.of(task));
        given(runs.findByTenantIdAndIdForUpdate("tenant-a", "run-terminal"))
                .willReturn(Optional.of(run));

        assertRejected(HttpStatus.CONFLICT, "SOAR_RUN_NOT_RESUMABLE",
                () -> service.completeManualTask("task-terminal", Map.of("confirmed", true)));
        verify(manualTasks, never()).save(any(SoarManualTaskEntity.class));
        verify(signals, never()).save(any(SoarSignalOutboxEntity.class));
    }

    @Test
    void resolveUnknownCannotReviveAPartiallySucceededRun() {
        SoarNodeRunEntity node = node("node-terminal", "run-terminal", "contain", "ACTION_UNKNOWN");
        SoarRunEntity run = run("run-terminal", SoarRunStatus.PARTIALLY_SUCCEEDED, "bob");
        given(nodes.findByTenantIdAndIdForUpdate("tenant-a", "node-terminal"))
                .willReturn(Optional.of(node));
        given(runs.findByTenantIdAndIdForUpdate("tenant-a", "run-terminal"))
                .willReturn(Optional.of(run));

        assertRejected(HttpStatus.CONFLICT, "SOAR_RUN_NOT_RESUMABLE",
                () -> service.resolveUnknown("node-terminal", "CONFIRMED_SUCCEEDED", "evidence", "reason"));
        verify(nodes, never()).save(any(SoarNodeRunEntity.class));
        verify(signals, never()).save(any(SoarSignalOutboxEntity.class));
    }

    @Test
    void decideApprovalCannotReopenAPartiallySucceededRun() {
        SoarApprovalEntity approval = approval("apr-terminal", "run-terminal", "PENDING");
        SoarRunEntity run = run("run-terminal", SoarRunStatus.PARTIALLY_SUCCEEDED, "bob");
        given(approvals.findByTenantIdAndIdForUpdate("tenant-a", "apr-terminal"))
                .willReturn(Optional.of(approval));
        given(runs.findByTenantIdAndIdForUpdate("tenant-a", "run-terminal"))
                .willReturn(Optional.of(run));

        assertRejected(HttpStatus.CONFLICT, "SOAR_RUN_NOT_RESUMABLE",
                () -> service.decideApproval("apr-terminal", true, "late decision"));
        verify(approvals, never()).save(any(SoarApprovalEntity.class));
        verify(runs, never()).save(any(SoarRunEntity.class));
        verify(signals, never()).save(any(SoarSignalOutboxEntity.class));
    }

    @Test
    void expireApprovalDoesNotMutateAPartiallySucceededRun() {
        SoarApprovalEntity approval = approval("apr-expired-terminal", "run-terminal", "PENDING");
        approval.setExpiresAt(Instant.now().minusSeconds(5));
        SoarRunEntity run = run("run-terminal", SoarRunStatus.PARTIALLY_SUCCEEDED, "bob");
        given(approvals.findByTenantIdAndIdForUpdate("tenant-a", "apr-expired-terminal"))
                .willReturn(Optional.of(approval));
        given(runs.findByTenantIdAndIdForUpdate("tenant-a", "run-terminal"))
                .willReturn(Optional.of(run));

        assertThat(service.expireApproval("apr-expired-terminal", Instant.now())).isFalse();
        assertThat(approval.getStatus()).isEqualTo("PENDING");
        verify(approvals, never()).save(any(SoarApprovalEntity.class));
        verify(signals, never()).save(any(SoarSignalOutboxEntity.class));
    }

    // -------------------------------------------------------------------- helpers

    private static void assertRejected(HttpStatus status, String code, ThrowingCallable call) {
        Throwable thrown = catchThrowable(call);
        assertThat(thrown).isInstanceOf(ResponseStatusException.class);
        assertThat(((ResponseStatusException) thrown).getStatusCode()).isEqualTo(status);
        assertThat(thrown.getMessage()).contains(code);
    }

    private static SoarRunEntity run(String id, SoarRunStatus status, String requestedBy) {
        SoarRunEntity run = new SoarRunEntity();
        run.setId(id);
        run.setTenantId("tenant-a");
        run.setRequestId("req-" + id);
        run.setExecutionSeriesId(id);
        run.setPlaybookId("pb-1");
        run.setPlaybookVersionId("ver-1");
        run.setPlaybookVersionNo(1);
        run.setDefinitionHash("hash-1");
        run.setTriggerType("MANUAL");
        run.setStatus(status.name());
        run.setRequestedBy(requestedBy);
        run.setInputJson("{\"subject\":{\"type\":\"alert\"},\"inputs\":{}}");
        run.setCreatedAt(Instant.now());
        run.setUpdatedAt(Instant.now());
        return run;
    }

    private static SoarApprovalEntity approval(String id, String runId, String status) {
        SoarApprovalEntity approval = new SoarApprovalEntity();
        approval.setId(id);
        approval.setTenantId("tenant-a");
        approval.setRunId(runId);
        approval.setApprovalKey(runId);
        approval.setStatus(status);
        approval.setRequestedBy("workflow");
        approval.setRequiredApprovals(1);
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
        outbox.setAttempts(0);
        outbox.setNextAttemptAt(Instant.now());
        outbox.setCreatedAt(Instant.now());
        outbox.setUpdatedAt(Instant.now());
        return outbox;
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

    private static SoarManualTaskEntity manualTask(String id, String status) {
        SoarManualTaskEntity task = new SoarManualTaskEntity();
        task.setId(id);
        task.setTenantId("tenant-a");
        task.setRunId("run-9");
        task.setNodeId("ack");
        task.setFormSchemaJson(FORM_SCHEMA);
        task.setStatus(status);
        task.setAssignee("bob");
        task.setCreatedAt(Instant.now());
        task.setUpdatedAt(Instant.now());
        return task;
    }
}
