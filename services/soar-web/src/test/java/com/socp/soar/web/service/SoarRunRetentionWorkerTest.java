package com.socp.soar.web.service;

import com.socp.soar.web.persistence.entity.SoarRunEntity;
import com.socp.soar.web.persistence.repository.SoarActionAttemptRepository;
import com.socp.soar.web.persistence.repository.SoarApprovalDecisionRepository;
import com.socp.soar.web.persistence.repository.SoarApprovalRepository;
import com.socp.soar.web.persistence.repository.SoarArtifactRepository;
import com.socp.soar.web.persistence.repository.SoarDispatchOutboxRepository;
import com.socp.soar.web.persistence.repository.SoarManualTaskRepository;
import com.socp.soar.web.persistence.repository.SoarNodeRunRepository;
import com.socp.soar.web.persistence.repository.SoarRunEventRepository;
import com.socp.soar.web.persistence.repository.SoarRunRepository;
import com.socp.soar.web.persistence.repository.SoarSignalOutboxRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SoarRunRetentionWorkerTest {

    private SoarRunRepository runs;
    private SoarNodeRunRepository nodes;
    private SoarActionAttemptRepository attempts;
    private SoarRunEventRepository events;
    private SoarRunRetentionWorker worker;
    private SoarDispatchOutboxRepository dispatches;
    private SoarApprovalRepository approvals;
    private SoarApprovalDecisionRepository approvalDecisions;
    private SoarManualTaskRepository manualTasks;
    private SoarSignalOutboxRepository signals;
    private SoarArtifactRepository artifacts;
    private SoarRunRetentionWorker fullWorker;

    @BeforeEach
    void setUp() {
        runs = mock(SoarRunRepository.class);
        nodes = mock(SoarNodeRunRepository.class);
        attempts = mock(SoarActionAttemptRepository.class);
        events = mock(SoarRunEventRepository.class);
        worker = new SoarRunRetentionWorker(runs, nodes, attempts, events);
        dispatches = mock(SoarDispatchOutboxRepository.class);
        approvals = mock(SoarApprovalRepository.class);
        approvalDecisions = mock(SoarApprovalDecisionRepository.class);
        manualTasks = mock(SoarManualTaskRepository.class);
        signals = mock(SoarSignalOutboxRepository.class);
        artifacts = mock(SoarArtifactRepository.class);
        fullWorker = new SoarRunRetentionWorker(runs, nodes, attempts, events, dispatches,
                approvals, approvalDecisions, manualTasks, signals, artifacts);
        ReflectionTestUtils.setField(worker, "runDays", 180L);
        ReflectionTestUtils.setField(worker, "eventDays", 365L);
        ReflectionTestUtils.setField(fullWorker, "runDays", 180L);
        ReflectionTestUtils.setField(fullWorker, "eventDays", 365L);
    }

    @Test
    void nothingToPurgeKeepsRepositoriesUntouched() {
        when(runs.findTop100ByStatusInAndUpdatedAtBeforeOrderByUpdatedAtAsc(anyCollection(), any()))
                .thenReturn(List.of());
        when(events.findIdsCreatedBefore(any(), any(Pageable.class))).thenReturn(List.of());

        worker.tick();

        verify(runs, never()).deleteByIds(any());
        verify(nodes, never()).deleteByRunIdIn(any());
        verify(events, never()).deleteByIds(any());
    }

    @Test
    void terminalRunFamilyIsPurgedWithChildren() {
        SoarRunEntity old = new SoarRunEntity();
        old.setId("run-old");
        old.setStatus("SUCCEEDED");
        when(runs.findTop100ByStatusInAndUpdatedAtBeforeOrderByUpdatedAtAsc(anyCollection(), any()))
                .thenReturn(List.of(old));
        when(nodes.findIdsByRunIdIn(anyCollection())).thenReturn(List.of("node-a", "node-b"));
        when(events.findIdsCreatedBefore(any(), any(Pageable.class))).thenReturn(List.of());

        worker.tick();

        verify(attempts).deleteByNodeRunIdIn(anyList());
        verify(nodes).deleteByRunIdIn(anyCollection());
        verify(runs).deleteByIds(anyCollection());
    }

    @Test
    void oldEventsArePurgedIndependently() {
        when(runs.findTop100ByStatusInAndUpdatedAtBeforeOrderByUpdatedAtAsc(anyCollection(), any()))
                .thenReturn(List.of());
        when(events.findIdsCreatedBefore(any(), any(Pageable.class))).thenReturn(List.of("evt-1"));

        worker.tick();

        verify(events).deleteByIds(anyCollection());
    }

    @Test
    void retainedTimelineOrArtifactBlocksParentDeletion() {
        SoarRunEntity old = oldRun("run-retained");
        when(runs.findTopPurgeableByStatusInAndUpdatedAtBefore(anyCollection(), any(), any(Pageable.class)))
                .thenReturn(List.of(old));
        when(events.findRunIdsByRunIdIn(anyCollection())).thenReturn(List.of(old.getId()));
        when(artifacts.findRunIdsByRunIdIn(anyCollection())).thenReturn(List.of());
        when(events.findIdsCreatedBefore(any(), any(Pageable.class))).thenReturn(List.of());

        fullWorker.tick();

        verify(runs, never()).deleteByIds(anyCollection());
        verify(nodes, never()).deleteByRunIdIn(anyCollection());
        verify(dispatches, never()).deleteByRunIdIn(anyCollection());
    }

    @Test
    void fullRunFamilyDeletesEveryForeignKeyChildBeforeParent() {
        SoarRunEntity old = oldRun("run-complete");
        when(runs.findTopPurgeableByStatusInAndUpdatedAtBefore(anyCollection(), any(), any(Pageable.class)))
                .thenReturn(List.of(old));
        when(runs.deleteByIds(anyCollection())).thenReturn(1);
        when(events.findRunIdsByRunIdIn(anyCollection())).thenReturn(List.of());
        when(artifacts.findRunIdsByRunIdIn(anyCollection())).thenReturn(List.of());
        when(nodes.findIdsByRunIdIn(anyCollection())).thenReturn(List.of("node-1"));
        when(approvals.findIdsByRunIdIn(anyCollection())).thenReturn(List.of("approval-1"));
        when(events.findIdsCreatedBefore(any(), any(Pageable.class))).thenReturn(List.of());

        fullWorker.tick();

        var order = inOrder(events, artifacts, nodes, attempts, approvals, approvalDecisions,
                dispatches, manualTasks, signals, runs);
        order.verify(events).findRunIdsByRunIdIn(anyCollection());
        order.verify(artifacts).findRunIdsByRunIdIn(anyCollection());
        order.verify(nodes).findIdsByRunIdIn(anyCollection());
        order.verify(attempts).deleteByNodeRunIdIn(anyCollection());
        order.verify(approvals).findIdsByRunIdIn(anyCollection());
        order.verify(approvalDecisions).deleteByApprovalIdIn(anyCollection());
        order.verify(dispatches).deleteByRunIdIn(anyCollection());
        order.verify(approvals).deleteByRunIdIn(anyCollection());
        order.verify(manualTasks).deleteByRunIdIn(anyCollection());
        order.verify(signals).deleteByRunIdIn(anyCollection());
        order.verify(nodes).deleteByRunIdIn(anyCollection());
        order.verify(runs).deleteByIds(anyCollection());
    }

    @Test
    void disabledRetentionSkipsEveryPass() {
        ReflectionTestUtils.setField(worker, "runDays", 0L);
        ReflectionTestUtils.setField(worker, "eventDays", 0L);

        worker.tick();

        verify(runs, never()).findTop100ByStatusInAndUpdatedAtBeforeOrderByUpdatedAtAsc(anyCollection(), any());
        verify(events, never()).findIdsCreatedBefore(any(), any(Pageable.class));
    }

    private static SoarRunEntity oldRun(String id) {
        SoarRunEntity run = new SoarRunEntity();
        run.setId(id);
        run.setStatus("SUCCEEDED");
        return run;
    }
}
