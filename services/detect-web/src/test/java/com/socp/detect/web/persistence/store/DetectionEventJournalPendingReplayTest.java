package com.socp.detect.web.persistence.store;

import com.socp.detect.web.persistence.entity.DetectionEventEntity;
import com.socp.detect.web.persistence.repository.DetectionEventRepository;
import com.socp.platform.tenant.context.TenantContext;
import com.socp.rule.model.Severity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Streams PENDING rows page by page up to a hard cap, so a restart or
 * rebalance never materialises the whole retention window before dispatch
 * (review 2026-09, scaling "Journal 重放一次性物化整个 24h PENDING 窗口").
 */
class DetectionEventJournalPendingReplayTest {

    private static final String INPUT_TOPIC = "socp-events";

    private DetectionEventRepository repository;
    private DetectionEventJournal journal;

    @BeforeEach
    void setUp() {
        TenantContext.set("tenant-a");
        repository = mock(DetectionEventRepository.class);
        // replayPageSize = 100 exercises multi-page paging below.
        journal = new DetectionEventJournal(repository, "24h", 100);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void stopsAtCapAndFetchesOnlyTheNeededPage() {
        when(repository.findByTenantStatusTopicAndKafkaPartitionInAfter(
                eq("tenant-a"), eq(DetectionEventStatus.PENDING.name()), eq(INPUT_TOPIC),
                any(Set.class), any(Instant.class), any(Pageable.class)))
                .thenReturn(rows(40));

        List<List<PendingDetectionEvent>> batches = new ArrayList<>();
        journal.replayPendingPages(Set.of(0), Duration.ofHours(24), 5, batches::add);

        int dispatched = batches.stream().mapToInt(List::size).sum();
        assertThat(dispatched).isEqualTo(5);
        assertThat(batches).allSatisfy(batch -> assertThat(batch.size()).isLessThanOrEqualTo(5));
        // The cap is satisfied within the first page, so exactly one read happens;
        // everything past the cap stays behind uncommitted Kafka offsets.
        verify(repository, times(1)).findByTenantStatusTopicAndKafkaPartitionInAfter(
                anyString(), anyString(), anyString(), any(Set.class), any(Instant.class), any(Pageable.class));
    }

    @Test
    void pagesAcrossBatchesAndTrimsToCap() {
        when(repository.findByTenantStatusTopicAndKafkaPartitionInAfter(
                eq("tenant-a"), eq(DetectionEventStatus.PENDING.name()), eq(INPUT_TOPIC),
                any(Set.class), any(Instant.class), any(Pageable.class)))
                .thenAnswer(invocation -> {
                    Pageable pageable = invocation.getArgument(5);
                    if (pageable.getPageNumber() == 0) {
                        return rows(100); // full page -> continue
                    }
                    return rows(60); // partial page, but capped at 150 overall
                });

        List<List<PendingDetectionEvent>> batches = new ArrayList<>();
        journal.replayPendingPages(Set.of(0, 1), Duration.ofHours(24), 150, batches::add);

        int dispatched = batches.stream().mapToInt(List::size).sum();
        assertThat(dispatched).isEqualTo(150);
        ArgumentCaptor<Pageable> pages = ArgumentCaptor.forClass(Pageable.class);
        verify(repository, times(2)).findByTenantStatusTopicAndKafkaPartitionInAfter(
                anyString(), anyString(), anyString(), any(Set.class), any(Instant.class), pages.capture());
        assertThat(pages.getAllValues().get(0).getPageNumber()).isZero();
        assertThat(pages.getAllValues().get(1).getPageNumber()).isEqualTo(1);
    }

    @Test
    void emptyResultDispatchesNothing() {
        when(repository.findByTenantStatusTopicAndKafkaPartitionInAfter(
                anyString(), anyString(), anyString(), any(Set.class), any(Instant.class),
                any(Pageable.class))).thenReturn(List.of());

        List<List<PendingDetectionEvent>> batches = new ArrayList<>();
        journal.replayPendingPages(Set.of(0), Duration.ofHours(24), 100, batches::add);

        assertThat(batches).isEmpty();
    }

    private static List<DetectionEventEntity> rows(int count) {
        List<DetectionEventEntity> rows = new ArrayList<>();
        Instant now = Instant.now();
        for (int i = 0; i < count; i++) {
            rows.add(new DetectionEventEntity("tenant-a", "event-" + now.getNano() + "-" + i,
                    "auth", "host-" + i, "raw", "{}", Severity.INFO.name(), now,
                    0, (long) i, "tenant-a|host|host-" + i));
        }
        return rows;
    }
}
