package com.socp.soar.web.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.socp.soar.web.persistence.entity.PlaybookVersionEntity;
import com.socp.soar.web.persistence.entity.SoarDispatchOutboxEntity;
import com.socp.soar.web.persistence.entity.SoarRunEntity;
import com.socp.soar.web.persistence.repository.PlaybookVersionRepository;
import com.socp.soar.web.persistence.repository.SoarDispatchOutboxRepository;
import com.socp.soar.web.persistence.repository.SoarRunRepository;
import com.socp.soar.web.temporal.request.SoarWorkflowRequest;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.client.WorkflowExecutionAlreadyStarted;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SoarDispatchWorkerCoverageTest {
    @Mock SoarDispatchOutboxRepository dispatches;
    @Mock SoarRunRepository runs;
    @Mock PlaybookVersionRepository versions;
    @Mock SoarDispatchState state;
    @Mock TemporalExecutor temporal;
    SoarDispatchWorker worker;
    SoarDispatchOutboxEntity candidate;
    SoarRunEntity run;
    PlaybookVersionEntity version;
    SoarDispatchState.Claim claim;

    @BeforeEach void setup() {
        worker = new SoarDispatchWorker(dispatches, runs, versions, state, temporal, new ObjectMapper());
        candidate = new SoarDispatchOutboxEntity();
        candidate.setId("out-1"); candidate.setTenantId("tenant-a"); candidate.setRunId("run-1");
        run = new SoarRunEntity();
        run.setId("run-1"); run.setTenantId("tenant-a"); run.setPlaybookVersionId("ver-1");
        run.setInputJson("{\"_soar\":{\"resumeFromNodeId\":\"node-7\"}}");
        version = new PlaybookVersionEntity();
        version.setId("ver-1"); version.setDefinitionJson("{\"limits\":{\"maxNodeExecutions\":12}}");
        claim = new SoarDispatchState.Claim("tenant-a", "out-1", "run-1", 1, "worker", run);
    }

    void poll() {
        when(temporal.isAvailable()).thenReturn(true);
        when(dispatches.findTop100ByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(eq("PENDING"), any()))
                .thenReturn(List.of(candidate));
    }

    void claimed() {
        poll();
        when(state.claim(eq(candidate), anyString(), any())).thenReturn(Optional.of(claim));
        when(versions.findByTenantIdAndId("tenant-a", "ver-1")).thenReturn(Optional.of(version));
    }

    void ready() {
        claimed();
        when(state.beforeStart(eq(claim), any())).thenReturn(true);
    }

    @Test void claimedPendingRowStartsTemporalWithStableWorkflowIdAndResumeBudget() {
        ready();
        when(temporal.startWorkflow(any(), eq(claim.workflowId())))
                .thenReturn(WorkflowExecution.newBuilder().setRunId("remote-1").build());
        worker.tick();
        var request = ArgumentCaptor.forClass(SoarWorkflowRequest.class);
        verify(temporal).startWorkflow(request.capture(), eq("soar-tenant-a-run-1"));
        assertEquals("tenant-a", request.getValue().tenantId());
        assertEquals("run-1", request.getValue().runId());
        assertEquals("node-7", request.getValue().resumeFromNodeId());
        assertEquals(12, request.getValue().executionBudgetLimit());
        verify(state).complete(eq(claim), eq("remote-1"), any());
        verify(dispatches, never()).save(any());
        verify(runs, never()).save(any());
    }

    @Test void typedMatchingDuplicateAcknowledgesOriginalExecution() {
        ready();
        when(temporal.startWorkflow(any(), anyString())).thenThrow(new WorkflowExecutionAlreadyStarted(
                WorkflowExecution.newBuilder().setWorkflowId(claim.workflowId()).setRunId("original").build(), "SoarWorkflow", null));
        worker.tick();
        verify(state).complete(eq(claim), eq("original"), any());
        verify(state, never()).fail(any(), any(), anyBoolean(), any());
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void arbitraryErrorTextAndAnotherWorkflowsDuplicateCannotAcknowledge(boolean wrongWorkflow) {
        ready();
        RuntimeException failure = wrongWorkflow ? new WorkflowExecutionAlreadyStarted(
                WorkflowExecution.newBuilder().setWorkflowId("other").build(), "SoarWorkflow", null)
                : new IllegalStateException("not already started; token=secret");
        when(temporal.startWorkflow(any(), anyString())).thenThrow(failure);
        worker.tick();
        verify(state).fail(eq(claim), argThat(text -> !text.contains("token=secret")), eq(false), any());
        verify(state, never()).complete(any(), any(), any());
    }

    @Test void unclaimedRowDoesNotCrossRemoteBoundary() {
        poll();
        worker.tick();
        verifyNoInteractions(versions);
        verify(temporal, never()).startWorkflow(any(), anyString());
    }

    @Test void unavailableTemporalDoesNotConsumeAnAttempt() {
        worker.tick();
        verify(state, never()).claim(any(), anyString(), any());
        verify(dispatches, never()).findTop100ByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(any(), any());
    }

    @Test void cancellationBeforeStartSkipsRemoteExecution() {
        claimed();
        worker.tick();
        verify(temporal, never()).startWorkflow(any(), anyString());
        verify(state, never()).complete(any(), any(), any());
    }

    @Test void cancellationAfterStartIsDeliveredEvenIfCompletionLosesOwnership() {
        ready();
        when(temporal.startWorkflow(any(), anyString())).thenReturn(WorkflowExecution.newBuilder().setRunId("remote").build());
        run.setStatus("CANCELLING");
        when(runs.findByTenantIdAndId("tenant-a", "run-1")).thenReturn(Optional.of(run));
        doThrow(new IllegalStateException("offline")).when(temporal).cancelWorkflow(claim.workflowId());
        worker.tick();
        verify(temporal).cancelWorkflow(claim.workflowId());
        verify(state, never()).fail(any(), any(), anyBoolean(), any());
    }

    @Test void persistenceFailureAfterAcceptanceDoesNotBecomeRemoteFailure() {
        ready();
        when(temporal.startWorkflow(any(), anyString())).thenReturn(WorkflowExecution.newBuilder().setRunId("remote").build());
        when(state.complete(eq(claim), anyString(), any())).thenThrow(new IllegalStateException("database offline"));
        worker.tick();
        verify(state, never()).fail(any(), any(), anyBoolean(), any());
    }

    @ParameterizedTest @ValueSource(strings = {"{broken", "null", "[]", "{} {}", "{\"_soar\":4}",
            "{\"_soar\":{\"resumeFromNodeId\":7}}", "{\"_soar\":{},\"_soar\":{}}"})
    void corruptResumeInputIsDeadLetteredWithoutStartingFromTheBeginning(String input) {
        claimed();
        run.setInputJson(input);
        worker.tick();
        verify(state).fail(eq(claim), startsWith("dispatch "), eq(true), any());
        verify(temporal, never()).startWorkflow(any(), anyString());
    }

    @ParameterizedTest @ValueSource(strings = {"null", "{", "{\"limits\":null}",
            "{\"limits\":{\"maxNodeExecutions\":\"7\"}}", "{\"limits\":{\"maxNodeExecutions\":0}}",
            "{\"limits\":{\"maxNodeExecutions\":501}}", "{\"limits\":{\"maxNodeExecutions\":2.5}}"})
    void corruptDefinitionCannotSilentlyUseDefaultExecutionBudget(String definition) {
        claimed();
        version.setDefinitionJson(definition);
        worker.tick();
        verify(state).fail(eq(claim), startsWith("dispatch "), eq(true), any());
        verify(temporal, never()).startWorkflow(any(), anyString());
    }

    @Test void fullRerunWithEmptyResumeMetadataUsesDefaultBudget() {
        ready();
        run.setInputJson("{\"_soar\":{\"resumeFromNodeId\":\"\"}}");
        version.setDefinitionJson("{}");
        when(temporal.startWorkflow(any(), anyString())).thenReturn(WorkflowExecution.newBuilder().build());
        worker.tick();
        var request = ArgumentCaptor.forClass(SoarWorkflowRequest.class);
        verify(temporal).startWorkflow(request.capture(), anyString());
        assertNull(request.getValue().resumeFromNodeId());
        assertEquals(500, request.getValue().executionBudgetLimit());
    }

    @Test void failureOfOneRecordDoesNotPreventClaimingTheNext() {
        when(temporal.isAvailable()).thenReturn(true);
        var other = new SoarDispatchOutboxEntity();
        when(dispatches.findTop100ByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(eq("PENDING"), any()))
                .thenReturn(List.of(candidate, other));
        when(state.claim(eq(candidate), anyString(), any())).thenThrow(new IllegalStateException("database unavailable"));
        worker.tick();
        verify(state).claim(eq(other), anyString(), any());
    }
}
