package com.socp.detect.web.persistence.store;

import com.socp.detect.web.persistence.entity.DetectionEventEntity;
import com.socp.detect.web.persistence.repository.DetectionEventRepository;
import com.socp.platform.tenant.context.TenantContext;
import com.socp.rule.model.SecurityEvent;
import com.socp.rule.model.Severity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DetectionEventJournalTest {

    private DetectionEventRepository repository;
    private DetectionEventJournal journal;

    @BeforeEach
    void setUp() {
        TenantContext.set("tenant-a");
        repository = mock(DetectionEventRepository.class);
        journal = new DetectionEventJournal(repository, "24h", 100);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void claimsNewEventsAndReturnsExistingLifecycleState() {
        SecurityEvent event = event("event-1", "tenant-a", Instant.now(), Map.of("message", "login"));
        when(repository.findByTenantIdAndSourceEventId("tenant-a", "event-1"))
                .thenReturn(Optional.empty());

        assertThat(journal.claim(event, 3, 41L, "tenant-a|host|h1"))
                .isEqualTo(DetectionEventClaim.NEW);

        ArgumentCaptor<DetectionEventEntity> saved = ArgumentCaptor.forClass(DetectionEventEntity.class);
        verify(repository).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getTenantId()).isEqualTo("tenant-a");
        assertThat(saved.getValue().getKafkaPartition()).isEqualTo(3);
        assertThat(saved.getValue().getKafkaOffset()).isEqualTo(41L);
        assertThat(saved.getValue().getStatus()).isEqualTo(DetectionEventStatus.PENDING.name());

        DetectionEventEntity existing = saved.getValue();
        when(repository.findByTenantIdAndSourceEventId("tenant-a", "event-1"))
                .thenReturn(Optional.of(existing));
        assertThat(journal.claim(event)).isEqualTo(DetectionEventClaim.PENDING);

        existing.setStatus(DetectionEventStatus.COMPLETED.name());
        assertThat(journal.claim(event)).isEqualTo(DetectionEventClaim.COMPLETED);
        existing.setStatus(DetectionEventStatus.DEAD_LETTERED.name());
        assertThat(journal.claim(event)).isEqualTo(DetectionEventClaim.DEAD_LETTERED);
    }

    @Test
    void rejectsInvalidEventsAndWrapsUnexpectedJournalFailures() {
        assertThatThrownBy(() -> journal.claim(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> journal.claim(event("", "tenant-a", Instant.now(), Map.of())))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> journal.claim(event("event-2", "bad tenant", Instant.now(), Map.of())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("tenant");

        SecurityEvent event = event("event-3", "tenant-a", null, Map.of());
        when(repository.findByTenantIdAndSourceEventId("tenant-a", "event-3"))
                .thenReturn(Optional.empty());
        when(repository.saveAndFlush(any(DetectionEventEntity.class)))
                .thenThrow(new RuntimeException("database unavailable"));

        assertThatThrownBy(() -> journal.claim(event))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unable to write detection event journal");
    }

    @Test
    void marksCompletedAndDeadLetteredWithoutOverwritingTerminalRows() {
        DetectionEventEntity row = row("event-4", "tenant-a", "{}");
        when(repository.findByTenantIdAndSourceEventId("tenant-a", "event-4"))
                .thenReturn(Optional.of(row));

        journal.markCompleted("event-4");
        assertThat(row.getStatus()).isEqualTo(DetectionEventStatus.COMPLETED.name());
        assertThat(row.getCompletedAt()).isNotNull();
        verify(repository).saveAndFlush(row);

        row.setStatus(DetectionEventStatus.DEAD_LETTERED.name());
        journal.markCompleted("tenant-a", "event-4");
        assertThat(row.getStatus()).isEqualTo(DetectionEventStatus.DEAD_LETTERED.name());

        journal.markDeadLettered("tenant-a", "event-4", "malformed payload");
        assertThat(row.getStatus()).isEqualTo(DetectionEventStatus.DEAD_LETTERED.name());
        assertThat(row.getDeadLetteredAt()).isNotNull();
        assertThat(row.getStatusReason()).isEqualTo("malformed payload");
        verify(repository, org.mockito.Mockito.times(2)).saveAndFlush(row);

        journal.markCompleted("tenant-a", "");
        journal.markDeadLettered("tenant-a", null, "ignored");
        verify(repository, never()).delete(any(DetectionEventEntity.class));
    }

    @Test
    void recordsDeadLetterRowsIdempotentlyAndRequiresTenantContext() {
        when(repository.findByTenantIdAndSourceEventId("tenant-a", "event-5"))
                .thenReturn(Optional.empty());
        journal.recordDeadLettered("event-5", "raw event", 2, 8L, "bad schema");

        ArgumentCaptor<DetectionEventEntity> captured = ArgumentCaptor.forClass(DetectionEventEntity.class);
        verify(repository).saveAndFlush(captured.capture());
        assertThat(captured.getValue().getStatus()).isEqualTo(DetectionEventStatus.DEAD_LETTERED.name());
        assertThat(captured.getValue().getStatusReason()).isEqualTo("bad schema");
        assertThat(captured.getValue().getKafkaOffset()).isEqualTo(8L);

        DetectionEventEntity existing = row("event-6", "tenant-a", "{}");
        when(repository.findByTenantIdAndSourceEventId("tenant-a", "event-6"))
                .thenReturn(Optional.of(existing));
        journal.recordDeadLettered("event-6", "ignored", 1, 2L, "duplicate");
        assertThat(existing.getStatusReason()).isEqualTo("duplicate");

        journal.recordDeadLettered("null", "ignored", null, null, "ignored");
        TenantContext.clear();
        journal.recordDeadLettered("event-without-tenant", "ignored", null, null, "ignored");
        verify(repository, org.mockito.Mockito.times(2)).saveAndFlush(any(DetectionEventEntity.class));
    }

    @Test
    void readsPagesRestoresFieldsAndFallsBackForMalformedRows() {
        DetectionEventEntity valid = new DetectionEventEntity(
                "tenant-a", "event-7", "auth", "host", "raw", "{\"user\":\"alice\"}",
                Severity.HIGH.name(), Instant.parse("2026-08-20T00:00:00Z"), 4, 7L,
                "tenant-a|host|host");
        valid.setStatus(DetectionEventStatus.COMPLETED.name());
        DetectionEventEntity invalid = new DetectionEventEntity(
                "tenant-a", "event-8", "auth", "host", "raw", "{}", "not-a-severity",
                Instant.parse("2026-08-21T00:00:00Z"), 4, 8L, "tenant-a|host|host");
        when(repository.findByTenantIdAndStatusAndOccurredAtAfterOrderByOccurredAtAscSourceEventIdAsc(
                eq("tenant-a"), anyString(), any(Instant.class), any(Pageable.class)))
                .thenReturn(List.of(invalid, valid), List.of());

        List<SecurityEvent> restored = journal.recent(Duration.ofHours(1));

        assertThat(restored).hasSize(2);
        assertThat(restored.get(0).id()).isEqualTo("event-7");
        assertThat(restored.get(0).get("tenant_id")).isEqualTo("tenant-a");
        assertThat(restored.get(0).get("user")).isEqualTo("alice");
        assertThat(restored.get(0).severity()).isEqualTo(Severity.HIGH);
        assertThat(restored.get(1).id()).isEqualTo("event-8");
        assertThat(restored.get(1).severity()).isEqualTo(Severity.INFO);
    }

    @Test
    void replaysPendingRecordsAndExposesCountsAndTenantScopedRemoval() {
        DetectionEventEntity pending = row("event-9", "tenant-a", "{}");
        when(repository.findByTenantIdAndStatusAndKafkaPartitionInAndOccurredAtAfterOrderByKafkaPosition(
                eq("tenant-a"), eq(DetectionEventStatus.PENDING.name()), eq(Set.of(4)),
                any(Instant.class), any(Pageable.class)))
                .thenReturn(List.of(pending));
        when(repository.countByTenantIdAndStatus("tenant-a", DetectionEventStatus.PENDING.name()))
                .thenReturn(3L, 2L);

        List<PendingDetectionEvent> records = journal.pendingRecordsForPartitions(
                Set.of(4), Duration.ofMinutes(5));
        assertThat(records).singleElement().satisfies(record -> {
            assertThat(record.event().id()).isEqualTo("event-9");
            assertThat(record.partition()).isEqualTo(4);
            assertThat(record.offset()).isEqualTo(9L);
        });
        assertThat(journal.pendingRecordsForPartitions(Set.of(), Duration.ofMinutes(5))).isEmpty();
        assertThat(journal.pendingCount()).isEqualTo(3L);
        assertThat(journal.pendingCount("tenant-a")).isEqualTo(2L);

        when(repository.findByTenantIdAndSourceEventId("tenant-a", "event-9"))
                .thenReturn(Optional.of(pending));
        journal.remove("event-9");
        verify(repository).delete(pending);
        journal.remove("tenant-a", "");
        journal.replayRecentForPartitions(Set.of(), Duration.ofHours(1), ignored -> {
            throw new AssertionError("empty partitions must not replay");
        });
    }

    @Test
    void systemScopeReadsBothCompletedAndPendingPartitions() {
        DetectionEventEntity completed = row("event-10", "tenant-a", "{}");
        completed.setStatus(DetectionEventStatus.COMPLETED.name());
        DetectionEventEntity pending = row("event-11", "tenant-b", "{}");
        when(repository.findByStatusAndOccurredAtAfterOrderByOccurredAtAscSourceEventIdAsc(
                anyString(), any(Instant.class), any(Pageable.class)))
                .thenReturn(List.of(completed));
        when(repository.findByStatusAndKafkaPartitionInAndOccurredAtAfterOrderByKafkaPosition(
                anyString(), eq(Set.of(4)), any(Instant.class), any(Pageable.class)))
                .thenReturn(List.of(completed));
        when(repository.countByStatus(DetectionEventStatus.PENDING.name())).thenReturn(4L);

        TenantContext.runAsSystem(() -> {
            assertThat(journal.recent(Duration.ofMinutes(5))).hasSize(1);
            assertThat(journal.recentForPartitions(Set.of(4), Duration.ofMinutes(5))).hasSize(1);
            List<List<SecurityEvent>> replayed = new java.util.ArrayList<>();
            journal.replayRecent(Duration.ofMinutes(5), replayed::add);
            journal.replayRecentForPartitions(Set.of(4), Duration.ofMinutes(5), replayed::add);
            assertThat(replayed).hasSize(2);
            assertThat(journal.pendingCount()).isEqualTo(4L);
        });

        when(repository.findByStatusAndKafkaPartitionInAndOccurredAtAfterOrderByKafkaPosition(
                eq(DetectionEventStatus.PENDING.name()), eq(Set.of(4)), any(Instant.class), any(Pageable.class)))
                .thenReturn(List.of(pending));
        TenantContext.runAsSystem(() -> {
            assertThat(journal.pendingForPartitions(Set.of(4), Duration.ofMinutes(5))).hasSize(1);
            assertThat(journal.pendingRecordsForPartitions(Set.of(4), Duration.ofMinutes(5)))
                    .singleElement().satisfies(record -> assertThat(record.event().tenantId()).isEqualTo("tenant-b"));
        });
        verify(repository).countByStatus(DetectionEventStatus.PENDING.name());
    }

    @Test
    void replaysCheckpointRowsWithAndWithoutPartitionFilterAndSkipsInvalidArguments() {
        DetectionEventEntity valid = row("event-12", "tenant-a", "{}");
        when(repository.findByTenantIdAndStatusAndCompletedAtAfterOrderByCompletedAt(
                eq("tenant-a"), anyString(), any(Instant.class), any(Pageable.class)))
                .thenReturn(List.of(valid));
        when(repository.findByTenantIdAndStatusAndKafkaPartitionInAndCompletedAtAfterOrderByCompletedAt(
                eq("tenant-a"), anyString(), eq(Set.of(2)), any(Instant.class), any(Pageable.class)))
                .thenReturn(List.of(valid));

        List<List<SecurityEvent>> batches = new java.util.ArrayList<>();
        journal.replayCompletedAfter("tenant-a", Instant.EPOCH, Set.of(), batches::add);
        journal.replayCompletedAfter("tenant-a", Instant.EPOCH, Set.of(2), batches::add);
        journal.replayCompletedAfter("", Instant.EPOCH, Set.of(), batches::add);
        journal.replayCompletedAfter("tenant-a", null, Set.of(), batches::add);
        journal.replayCompletedAfter("tenant-a", Instant.EPOCH, Set.of(), null);
        assertThat(batches).hasSize(2);
        assertThat(journal.recoveryWindow()).contains("PT");
        assertThat(journal.supportsCheckpointReplay()).isTrue();
    }

    @Test
    void constructorClampsPoliciesAndFromRowsSkipsMalformedJson() {
        DetectionEventJournal configured = new DetectionEventJournal(repository, "", 1,
                "0", "not-a-duration");
        assertThat(configured.retention()).isEqualTo(Duration.ofHours(24));
        DetectionEventEntity malformed = row("event-13", "tenant-a", "not-json");
        when(repository.findByTenantIdAndStatusAndOccurredAtAfterOrderByOccurredAtAscSourceEventIdAsc(
                eq("tenant-a"), anyString(), any(Instant.class), any(Pageable.class)))
                .thenReturn(List.of(malformed));
        assertThat(configured.recent(Duration.ZERO)).isEmpty();
    }

    @Test
    void cleanupDefersWhenRepositoryMaintenanceFails() {
        when(repository.deleteCompletedBatchBefore(anyString(), any(Instant.class), anyInt()))
                .thenThrow(new RuntimeException("database unavailable"));

        journal.cleanupExpiredTerminalEvents();

        verify(repository).deleteCompletedBatchBefore(eq(DetectionEventStatus.COMPLETED.name()),
                any(Instant.class), eq(1_000));
        verify(repository, never()).deleteDeadLetteredBatchBefore(
                anyString(), any(Instant.class), anyInt());
    }

    private static SecurityEvent event(String id, String tenant, Instant timestamp,
                                       Map<String, String> fields) {
        Map<String, String> copy = new java.util.LinkedHashMap<>(fields);
        copy.put("tenant_id", tenant);
        return new SecurityEvent(id, timestamp, "auth", "host", "raw", copy, Severity.HIGH);
    }

    private static DetectionEventEntity row(String eventId, String tenant, String fields) {
        return new DetectionEventEntity(tenant, eventId, "auth", "host", "raw", fields,
                Severity.HIGH.name(), Instant.now(), 4, Long.parseLong(eventId.substring(6)),
                "tenant-a|host|host");
    }
}
