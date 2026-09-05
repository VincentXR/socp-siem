package com.socp.soar.web.service;

import com.socp.soar.web.persistence.entity.SoarRunEntity;
import com.socp.soar.web.persistence.repository.SoarActionAttemptRepository;
import com.socp.soar.web.persistence.repository.SoarNodeRunRepository;
import com.socp.soar.web.persistence.repository.SoarRunEventRepository;
import com.socp.soar.web.persistence.repository.SoarRunRepository;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SoarRunRetentionWorkerTest {

    private SoarRunRepository runs;
    private SoarNodeRunRepository nodes;
    private SoarActionAttemptRepository attempts;
    private SoarRunEventRepository events;
    private SoarRunRetentionWorker worker;

    @BeforeEach
    void setUp() {
        runs = mock(SoarRunRepository.class);
        nodes = mock(SoarNodeRunRepository.class);
        attempts = mock(SoarActionAttemptRepository.class);
        events = mock(SoarRunEventRepository.class);
        worker = new SoarRunRetentionWorker(runs, nodes, attempts, events);
        ReflectionTestUtils.setField(worker, "runDays", 180L);
        ReflectionTestUtils.setField(worker, "eventDays", 365L);
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
    void disabledRetentionSkipsEveryPass() {
        ReflectionTestUtils.setField(worker, "runDays", 0L);
        ReflectionTestUtils.setField(worker, "eventDays", 0L);

        worker.tick();

        verify(runs, never()).findTop100ByStatusInAndUpdatedAtBeforeOrderByUpdatedAtAsc(anyCollection(), any());
        verify(events, never()).findIdsCreatedBefore(any(), any(Pageable.class));
    }
}
