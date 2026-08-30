package com.socp.detect.web.persistence.store;


import com.socp.detect.web.persistence.repository.DetectionEventRepository;
import com.socp.platform.tenant.context.TenantContext;
import com.socp.detect.web.persistence.entity.DetectionEventEntity;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DetectionEventJournalReplayTest {

    @Test
    void partitionReplayDeliversBoundedPagesWithoutAccumulatingAllRows() {
        TenantContext.set("tenant-a");
        DetectionEventRepository repository = mock(DetectionEventRepository.class);
        List<DetectionEventEntity> first = rows(0, 100);
        List<DetectionEventEntity> second = rows(100, 1);
        when(repository.findByTenantIdAndStatusAndKafkaPartitionInAndOccurredAtAfterOrderByKafkaPosition(
                eq("tenant-a"), anyString(), eq(Set.of(2)), any(Instant.class), any(Pageable.class)))
                .thenAnswer(invocation -> {
                    Pageable page = invocation.getArgument(4);
                    return page.getPageNumber() == 0 ? first : second;
                });

        DetectionEventJournal journal = new DetectionEventJournal(repository, "24h", 100);
        List<Integer> deliveredPageSizes = new ArrayList<>();
        journal.replayRecentForPartitions(
                Set.of(2), Duration.ofHours(1), batch -> deliveredPageSizes.add(batch.size()));

        assertEquals(List.of(100, 1), deliveredPageSizes);
        TenantContext.clear();
    }

    @Test
    void retentionCleanupUsesIndependentCompletedAndDeadLetterPolicies() {
        DetectionEventRepository repository = mock(DetectionEventRepository.class);
        when(repository.deleteCompletedBatchBefore(anyString(), any(Instant.class), anyInt())).thenReturn(5);
        when(repository.deleteDeadLetteredBatchBefore(anyString(), any(Instant.class), anyInt())).thenReturn(2);

        DetectionEventJournal journal = new DetectionEventJournal(repository, "24h", 100);
        journal.cleanupExpiredTerminalEvents();

        verify(repository).deleteCompletedBatchBefore(
                eq(DetectionEventStatus.COMPLETED.name()), any(Instant.class), eq(1_000));
        verify(repository).deleteDeadLetteredBatchBefore(
                eq(DetectionEventStatus.DEAD_LETTERED.name()), any(Instant.class), eq(1_000));
    }

    @Test
    void retentionPoliciesAcceptDayUnits() {
        DetectionEventRepository repository = mock(DetectionEventRepository.class);
        DetectionEventJournal journal = new DetectionEventJournal(repository, "24h", 100,
                "2d", "3d");
        journal.cleanupExpiredTerminalEvents();

        ArgumentCaptor<Instant> completedCutoff = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Instant> deadLetterCutoff = ArgumentCaptor.forClass(Instant.class);
        verify(repository).deleteCompletedBatchBefore(eq(DetectionEventStatus.COMPLETED.name()),
                completedCutoff.capture(), eq(1_000));
        verify(repository).deleteDeadLetteredBatchBefore(eq(DetectionEventStatus.DEAD_LETTERED.name()),
                deadLetterCutoff.capture(), eq(1_000));
        Instant now = Instant.now();
        assertEquals(Duration.ofDays(2).toSeconds(),
                Duration.between(completedCutoff.getValue(), now).toSeconds(), 2);
        assertEquals(Duration.ofDays(3).toSeconds(),
                Duration.between(deadLetterCutoff.getValue(), now).toSeconds(), 2);
    }

    @Test
    void nonPositiveReplayRetentionFallsBackToOneDay() {
        DetectionEventJournal journal = new DetectionEventJournal(mock(DetectionEventRepository.class),
                "-1d", 100);

        assertEquals(Duration.ofHours(24), journal.retention());
    }

    @Test
    void completedRetentionCannotUndercutReplayWindow() {
        DetectionEventRepository repository = mock(DetectionEventRepository.class);
        DetectionEventJournal journal = new DetectionEventJournal(repository, "7d", 100,
                "1d", "30d");
        when(repository.deleteCompletedBatchBefore(anyString(), any(Instant.class), anyInt())).thenReturn(0);

        journal.cleanupExpiredTerminalEvents();

        ArgumentCaptor<Instant> cutoff = ArgumentCaptor.forClass(Instant.class);
        verify(repository).deleteCompletedBatchBefore(eq(DetectionEventStatus.COMPLETED.name()),
                cutoff.capture(), eq(1_000));
        assertEquals(Duration.ofDays(7).toSeconds(),
                Duration.between(cutoff.getValue(), Instant.now()).toSeconds(), 2);
    }

    @Test
    void terminalCleanupIsBatchBoundedAndNeverTargetsPendingRows() {
        DetectionEventRepository repository = mock(DetectionEventRepository.class);
        when(repository.deleteCompletedBatchBefore(anyString(), any(Instant.class), eq(100)))
                .thenReturn(100, 100, 100);
        when(repository.deleteDeadLetteredBatchBefore(anyString(), any(Instant.class), eq(100)))
                .thenReturn(0);
        DetectionEventJournal journal = new DetectionEventJournal(repository, "24h", 100,
                "7d", "90d", 100, 2);

        journal.cleanupExpiredTerminalEvents();

        verify(repository, times(2)).deleteCompletedBatchBefore(
                eq(DetectionEventStatus.COMPLETED.name()), any(Instant.class), eq(100));
        verify(repository, never()).deleteCompletedBatchBefore(
                eq(DetectionEventStatus.PENDING.name()), any(Instant.class), anyInt());
        verify(repository, never()).deleteDeadLetteredBatchBefore(
                eq(DetectionEventStatus.PENDING.name()), any(Instant.class), anyInt());
    }

    private static List<DetectionEventEntity> rows(int start, int count) {
        List<DetectionEventEntity> rows = new ArrayList<>();
        for (int index = start; index < start + count; index++) {
            rows.add(new DetectionEventEntity(
                    "event-" + index, "system", "host-" + index, "heartbeat", "{}", "INFO",
                    Instant.parse("2026-08-19T00:00:00Z").plusSeconds(index),
                    2, (long) index, "default|host|host-" + index));
        }
        return rows;
    }
}
