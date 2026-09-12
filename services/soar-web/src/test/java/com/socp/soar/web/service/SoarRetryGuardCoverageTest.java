package com.socp.soar.web.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.socp.platform.tenant.context.TenantContext;
import com.socp.soar.web.definition.SoarDefinitionValidator;
import com.socp.soar.web.domain.DefinitionValidationResult;
import com.socp.soar.web.domain.SoarRunStatus;
import com.socp.soar.web.persistence.entity.PlaybookVersionEntity;
import com.socp.soar.web.persistence.entity.SoarDispatchOutboxEntity;
import com.socp.soar.web.persistence.entity.SoarNodeRunEntity;
import com.socp.soar.web.persistence.entity.SoarRunEntity;
import com.socp.soar.web.persistence.entity.SoarRunEventEntity;
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
 * Retry guard coverage for {@link SoarService#retryRun}: state-machine
 * rejections (non-terminal runs, unresolved ACTION_UNKNOWN results) and the
 * happy paths that resume a FAILED/TIMED_OUT run inside the same execution
 * series. Every collaborator is a Mockito mock; nothing touches Temporal,
 * the network or a database.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SoarRetryGuardCoverageTest {

    private static final String SAFE_DEFINITION = "{\"schemaVersion\":\"soar.playbook\","
            + "\"entryNodeId\":\"start\",\"nodes\":["
            + "{\"id\":\"start\",\"type\":\"START\",\"name\":\"Start\"},"
            + "{\"id\":\"notify\",\"type\":\"ACTION\",\"actionRef\":\"socp.notify/send-channel\"},"
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

    private SoarService service;

    @BeforeEach
    void setUp() {
        TenantContext.set("tenant-a");
        SoarTestIdentity.setOperator();
        service = new SoarService(playbooks, versions, runs, dispatches, nodes, events, approvals,
                validator, mapper, temporal, attempts, manualTasks, signals, null, null);
        service.setArtifacts(artifacts);
        // appendEvent locks the owning run before allocating a sequence number.
        given(runs.findByTenantIdAndIdForUpdate(eq("tenant-a"), anyString()))
                .willReturn(Optional.of(run("run-lock", "req-lock", SoarRunStatus.QUEUED)));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        SoarTestIdentity.clear();
    }

    @Test
    void retryRunRejectsRunsInNonTerminalStates() {
        given(runs.findByTenantIdAndId("tenant-a", "run-1"))
                .willReturn(Optional.of(run("run-1", "req-1", SoarRunStatus.RUNNING)));

        assertRejected(HttpStatus.CONFLICT, "SOAR_RUN_NOT_RETRYABLE",
                () -> service.retryRun("run-1", "try again"));
        verify(runs, never()).save(any(SoarRunEntity.class));
        verify(dispatches, never()).save(any(SoarDispatchOutboxEntity.class));
    }

    @Test
    void retryRunRejectsRunsWithStatusActionUnknown() {
        given(runs.findByTenantIdAndId("tenant-a", "run-1"))
                .willReturn(Optional.of(run("run-1", "req-1", SoarRunStatus.ACTION_UNKNOWN)));

        assertRejected(HttpStatus.CONFLICT, "SOAR_RUN_NOT_RETRYABLE",
                () -> service.retryRun("run-1", "try again"));
        verify(runs, never()).save(any(SoarRunEntity.class));
    }

    @Test
    void retryRunRejectsFailedRunCarryingAnUnresolvedUnknownNode() {
        SoarRunEntity original = run("run-1", "req-1", SoarRunStatus.FAILED);
        given(runs.findByTenantIdAndId("tenant-a", "run-1")).willReturn(Optional.of(original));
        given(nodes.findByTenantIdAndRunIdOrderByUpdatedAtAsc("tenant-a", "run-1"))
                .willReturn(List.of(node("node-1", "run-1", "contain", "ACTION_UNKNOWN")));

        assertRejected(HttpStatus.CONFLICT, "SOAR_RUN_NOT_RETRYABLE",
                () -> service.retryRun("run-1", "try again"));
        verify(runs, never()).save(any(SoarRunEntity.class));
    }

    @Test
    void retryRunRejectsUnknownRunIds() {
        given(runs.findByTenantIdAndId("tenant-a", "missing")).willReturn(Optional.empty());

        assertRejected(HttpStatus.NOT_FOUND, "SOAR_RUN_NOT_FOUND",
                () -> service.retryRun("missing", "try again"));
    }

    @Test
    void retryRunResumesAFailedRunFromItsFirstFailedNode() {
        SoarRunEntity original = run("run-1", "req-1", SoarRunStatus.FAILED);
        original.setExecutionSeriesId("series-1");
        original.setPlaybookVersionId("ver-1");
        original.setOutputJson(null);
        given(runs.findByTenantIdAndId("tenant-a", "run-1")).willReturn(Optional.of(original));
        given(nodes.findByTenantIdAndRunIdOrderByUpdatedAtAsc("tenant-a", "run-1"))
                .willReturn(List.of(
                        node("node-ok", "run-1", "notify", "SUCCEEDED"),
                        node("node-bad", "run-1", "contain", "FAILED")));
        given(runs.findByTenantIdAndRequestId(eq("tenant-a"), anyString())).willReturn(Optional.empty());
        given(versions.findByTenantIdAndId("tenant-a", "ver-1"))
                .willReturn(Optional.of(version("pb-1", "ver-1", "PUBLISHED", SAFE_DEFINITION)));
        given(validator.validate(anyString())).willReturn(validation(true, 0));

        Map<String, Object> result = service.retryRun("run-1", "transient connector outage");

        assertThat(result).containsEntry("duplicate", false)
                .containsEntry("triggerType", "RETRY")
                .containsEntry("status", SoarRunStatus.QUEUED.name());
        assertThat(String.valueOf(result.get("runId"))).isNotEqualTo("run-1");
        assertThat(String.valueOf(result.get("executionSeriesId"))).isEqualTo("series-1");
        assertThat(String.valueOf(result.get("requestId"))).startsWith("retry-run-1-");

        ArgumentCaptor<SoarRunEntity> runCaptor = ArgumentCaptor.forClass(SoarRunEntity.class);
        verify(runs).save(runCaptor.capture());
        SoarRunEntity clone = runCaptor.getValue();
        assertThat(clone.getTriggerType()).isEqualTo("RETRY");
        assertThat(clone.getStatus()).isEqualTo(SoarRunStatus.QUEUED.name());
        assertThat(clone.getExecutionSeriesId()).isEqualTo("series-1");
        assertThat(clone.getPlaybookId()).isEqualTo("pb-1");
        assertThat(clone.getRequestedBy()).isEqualTo("operator");
        assertThat(clone.getInputJson()).contains("\"resumeFromNodeId\":\"contain\"");

        ArgumentCaptor<SoarDispatchOutboxEntity> outboxCaptor =
                ArgumentCaptor.forClass(SoarDispatchOutboxEntity.class);
        verify(dispatches).save(outboxCaptor.capture());
        assertThat(outboxCaptor.getValue().getStatus()).isEqualTo("PENDING");
        assertThat(outboxCaptor.getValue().getRunId()).isEqualTo(clone.getId());
        assertThat(outboxCaptor.getValue().getAttempts()).isZero();

        ArgumentCaptor<SoarRunEventEntity> eventCaptor = ArgumentCaptor.forClass(SoarRunEventEntity.class);
        verify(events).save(eventCaptor.capture());
        assertThat(eventCaptor.getValue().getEventType()).isEqualTo("RUN_RETRY_QUEUED");
        verify(approvals, never()).save(any(com.socp.soar.web.persistence.entity.SoarApprovalEntity.class));
    }

    @Test
    void retryRunAllowsTimedOutRunsWithoutAFailedNodeToResume() {
        SoarRunEntity original = run("run-2", "req-2", SoarRunStatus.TIMED_OUT);
        original.setExecutionSeriesId("series-2");
        original.setPlaybookVersionId("ver-1");
        given(runs.findByTenantIdAndId("tenant-a", "run-2")).willReturn(Optional.of(original));
        given(nodes.findByTenantIdAndRunIdOrderByUpdatedAtAsc("tenant-a", "run-2"))
                .willReturn(List.of(node("node-ok", "run-2", "notify", "SUCCEEDED")));
        given(runs.findByTenantIdAndRequestId(eq("tenant-a"), anyString())).willReturn(Optional.empty());
        given(versions.findByTenantIdAndId("tenant-a", "ver-1"))
                .willReturn(Optional.of(version("pb-1", "ver-1", "PUBLISHED", SAFE_DEFINITION)));
        given(validator.validate(anyString())).willReturn(validation(true, 0));

        Map<String, Object> result = service.retryRun("run-2", "workflow timed out");

        assertThat(result).containsEntry("status", SoarRunStatus.QUEUED.name())
                .containsEntry("duplicate", false);
        ArgumentCaptor<SoarRunEntity> runCaptor = ArgumentCaptor.forClass(SoarRunEntity.class);
        verify(runs).save(runCaptor.capture());
        assertThat(runCaptor.getValue().getInputJson()).contains("\"resumeFromNodeId\":\"\"");
    }

    @Test
    void retryRunReturnsTheExistingCloneWhenTheRetryRequestIsRepeated() {
        SoarRunEntity original = run("run-1", "req-1", SoarRunStatus.FAILED);
        original.setPlaybookVersionId("ver-1");
        given(runs.findByTenantIdAndId("tenant-a", "run-1")).willReturn(Optional.of(original));
        given(nodes.findByTenantIdAndRunIdOrderByUpdatedAtAsc("tenant-a", "run-1")).willReturn(List.of());
        given(runs.findByTenantIdAndRequestId(eq("tenant-a"), anyString()))
                .willReturn(Optional.of(run("run-clone", "retry-run-1-abc", SoarRunStatus.QUEUED)));

        Map<String, Object> result = service.retryRun("run-1", "same reason twice");

        assertThat(result).containsEntry("duplicate", true).containsEntry("runId", "run-clone");
        verify(runs, never()).save(any(SoarRunEntity.class));
        verify(dispatches, never()).save(any(SoarDispatchOutboxEntity.class));
    }

    @Test
    void rerunRequiresExplicitConfirmationBeforeTouchingRepositories() {
        assertRejected(HttpStatus.CONFLICT, "SOAR_RERUN_CONFIRMATION_REQUIRED",
                () -> service.rerun("run-1", "because", false));
        verify(runs, never()).findByTenantIdAndId(anyString(), anyString());
        verify(runs, never()).save(any(SoarRunEntity.class));
    }

    // -------------------------------------------------------------------- helpers

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
}
