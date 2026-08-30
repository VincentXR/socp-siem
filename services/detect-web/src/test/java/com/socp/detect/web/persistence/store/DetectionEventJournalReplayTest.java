package com.socp.detect.web.persistence.store;



import com.socp.detect.web.persistence.store.*;
import com.socp.detect.web.persistence.repository.*;
import com.socp.detect.web.persistence.entity.*;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DetectionEventJournalReplayTest {

    @Test
    void partitionReplayDeliversBoundedPagesWithoutAccumulatingAllRows() {
        DetectionEventRepository repository = mock(DetectionEventRepository.class);
        List<DetectionEventEntity> first = rows(0, 100);
        List<DetectionEventEntity> second = rows(100, 1);
        when(repository.findByStatusAndKafkaPartitionInAndOccurredAtAfterOrderByKafkaPosition(
                anyString(), eq(Set.of(2)), any(Instant.class), any(Pageable.class)))
                .thenAnswer(invocation -> {
                    Pageable page = invocation.getArgument(3);
                    return page.getPageNumber() == 0 ? first : second;
                });

        DetectionEventJournal journal = new DetectionEventJournal(repository, "24h", 100);
        List<Integer> deliveredPageSizes = new ArrayList<>();
        journal.replayRecentForPartitions(
                Set.of(2), Duration.ofHours(1), batch -> deliveredPageSizes.add(batch.size()));

        assertEquals(List.of(100, 1), deliveredPageSizes);
    }

    @Test
    void retentionCleanupUsesIndependentCompletedAndDeadLetterPolicies() {
        DetectionEventRepository repository = mock(DetectionEventRepository.class);
        when(repository.deleteCompletedBefore(anyString(), any(Instant.class))).thenReturn(5L);
        when(repository.deleteDeadLetteredBefore(anyString(), any(Instant.class))).thenReturn(2L);

        DetectionEventJournal journal = new DetectionEventJournal(repository, "24h", 100);
        journal.cleanupExpiredTerminalEvents();

        verify(repository).deleteCompletedBefore(
                eq(DetectionEventStatus.COMPLETED.name()), any(Instant.class));
        verify(repository).deleteDeadLetteredBefore(
                eq(DetectionEventStatus.DEAD_LETTERED.name()), any(Instant.class));
    }

    @Test
    void retentionPoliciesAcceptDayUnits() {
        DetectionEventRepository repository = mock(DetectionEventRepository.class);
        DetectionEventJournal journal = new DetectionEventJournal(repository, "24h", 100,
                "2d", "3d");
        journal.cleanupExpiredTerminalEvents();

        ArgumentCaptor<Instant> completedCutoff = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Instant> deadLetterCutoff = ArgumentCaptor.forClass(Instant.class);
        verify(repository).deleteCompletedBefore(eq(DetectionEventStatus.COMPLETED.name()),
                completedCutoff.capture());
        verify(repository).deleteDeadLetteredBefore(eq(DetectionEventStatus.DEAD_LETTERED.name()),
                deadLetterCutoff.capture());
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
        when(repository.deleteCompletedBefore(anyString(), any(Instant.class))).thenReturn(0L);

        journal.cleanupExpiredTerminalEvents();

        ArgumentCaptor<Instant> cutoff = ArgumentCaptor.forClass(Instant.class);
        verify(repository).deleteCompletedBefore(eq(DetectionEventStatus.COMPLETED.name()),
                cutoff.capture());
        assertEquals(Duration.ofDays(7).toSeconds(),
                Duration.between(cutoff.getValue(), Instant.now()).toSeconds(), 2);
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
