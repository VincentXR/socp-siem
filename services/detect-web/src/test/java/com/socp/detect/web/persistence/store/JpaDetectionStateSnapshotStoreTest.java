package com.socp.detect.web.persistence.store;

import com.socp.detect.web.persistence.entity.DetectionStateSnapshotEntity;
import com.socp.detect.web.persistence.repository.DetectionStateSnapshotRepository;
import com.socp.rule.state.DetectionStateSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class JpaDetectionStateSnapshotStoreTest {

    @Mock
    private DetectionStateSnapshotRepository repository;

    @Test
    void roundTripsPartitionCheckpointVectorAlongsideState() throws Exception {
        DetectionStateSnapshot snapshot = new DetectionStateSnapshot(
                "rule-1", "v3", "tenant-a", 2, 14L, new byte[]{1, 2, 3},
                Instant.parse("2026-09-10T12:00:00Z"), Map.of(0, 8L, 2, 14L));
        when(repository.findByTenantIdAndRuleIdAndShardId("tenant-a", "rule-1", 2))
                .thenReturn(Optional.empty());

        JpaDetectionStateSnapshotStore store = new JpaDetectionStateSnapshotStore(repository);
        assertTrue(store.supportsAtomicBatch());
        store.save(snapshot);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<DetectionStateSnapshotEntity>> captor =
                (ArgumentCaptor<List<DetectionStateSnapshotEntity>>) (ArgumentCaptor<?>) ArgumentCaptor.forClass(List.class);
        verify(repository).saveAllAndFlush(captor.capture());
        DetectionStateSnapshotEntity persisted = captor.getValue().get(0);
        assertEquals("tenant-a", persisted.getTenantId());
        assertEquals("rule-1", persisted.getRuleId());
        persisted.setRowVersion(3L);
        assertEquals(3L, persisted.getRowVersion());
        assertEquals(Map.of("0", 8L, "2", 14L),
                com.socp.rule.util.Json.mapper().readValue(persisted.getPartitionOffsetsJson(),
                        new com.fasterxml.jackson.core.type.TypeReference<Map<String, Long>>() {}));

        when(repository.findByTenantIdAndRuleIdAndShardId("tenant-a", "rule-1", 2))
                .thenReturn(Optional.of(persisted));
        DetectionStateSnapshot restored = store.latest("tenant-a", "rule-1", 2).orElseThrow();
        assertEquals(snapshot.ruleVersion(), restored.ruleVersion());
        assertEquals(snapshot.lastProcessedOffset(), restored.lastProcessedOffset());
        assertArrayEquals(snapshot.serializedState(), restored.serializedState());
        assertEquals(snapshot.partitionOffsets(), restored.partitionOffsets());
    }

    @Test
    void doesNotOverwriteNewerCheckpoint() {
        DetectionStateSnapshotEntity persisted = new DetectionStateSnapshotEntity();
        persisted.setSnapshotTimestamp(Instant.parse("2026-09-10T12:00:01Z"));
        when(repository.findByTenantIdAndRuleIdAndShardId("tenant-a", "rule-1", 0))
                .thenReturn(Optional.of(persisted));

        new JpaDetectionStateSnapshotStore(repository).save(new DetectionStateSnapshot(
                "rule-1", "v3", "tenant-a", 0, 10L, new byte[]{1},
                Instant.parse("2026-09-10T12:00:00Z"), Map.of(0, 10L)));

        org.mockito.Mockito.verify(repository, org.mockito.Mockito.never()).saveAllAndFlush(any());
    }

    @Test
    void rejectsEntireGenerationBeforeMutatingManagedRows() {
        Instant checkpoint = Instant.parse("2026-09-10T12:00:00Z");
        DetectionStateSnapshotEntity first = row(checkpoint.minusSeconds(1), "{\"0\":8}");
        DetectionStateSnapshotEntity second = row(checkpoint.plusSeconds(1), "{\"0\":12}");
        when(repository.findByTenantIdAndRuleIdAndShardId("tenant-a", "first", 0)).thenReturn(Optional.of(first));
        when(repository.findByTenantIdAndRuleIdAndShardId("tenant-a", "second", 0)).thenReturn(Optional.of(second));

        new JpaDetectionStateSnapshotStore(repository).saveAll(List.of(
                snapshot("first", checkpoint, Map.of(0, 10L)), snapshot("second", checkpoint, Map.of(0, 10L))));

        assertEquals(checkpoint.minusSeconds(1), first.getSnapshotTimestamp());
        assertEquals("{\"0\":8}", first.getPartitionOffsetsJson());
        org.mockito.Mockito.verify(repository, org.mockito.Mockito.never()).saveAllAndFlush(any());
    }

    @Test
    void laterClockCannotOverwriteRegressingOrMissingPartition() {
        Instant checkpoint = Instant.parse("2026-09-10T12:00:00Z");
        DetectionStateSnapshotEntity row = row(checkpoint, "{\"0\":10,\"1\":20}");
        when(repository.findByTenantIdAndRuleIdAndShardId("tenant-a", "first", 0)).thenReturn(Optional.of(row));
        var store = new JpaDetectionStateSnapshotStore(repository);
        store.save(snapshot("first", checkpoint.plusSeconds(1), Map.of(0, 11L, 1, 19L)));
        store.save(snapshot("first", checkpoint.plusSeconds(2), Map.of(0, 12L)));
        assertEquals(checkpoint, row.getSnapshotTimestamp());
        org.mockito.Mockito.verify(repository, org.mockito.Mockito.never()).saveAllAndFlush(any());
    }

    @Test
    void advancedOffsetsSurviveClockSkew() {
        Instant checkpoint = Instant.parse("2026-09-10T12:00:00Z");
        DetectionStateSnapshotEntity row = row(checkpoint, "{\"0\":10}");
        when(repository.findByTenantIdAndRuleIdAndShardId("tenant-a", "first", 0)).thenReturn(Optional.of(row));
        new JpaDetectionStateSnapshotStore(repository).save(snapshot("first", checkpoint.minusSeconds(1), Map.of(0, 11L)));
        verify(repository).saveAllAndFlush(any());
        assertEquals("{\"0\":11}", row.getPartitionOffsetsJson());
    }

    @Test
    void rejectsMixedAndDuplicateGenerationInputsBeforeReadingRows() {
        var store = new JpaDetectionStateSnapshotStore(repository);
        Instant checkpoint = Instant.parse("2026-09-10T12:00:00Z");
        var first = snapshot("first", checkpoint, Map.of(0, 10L));
        assertThrows(IllegalArgumentException.class, () -> store.saveAll(List.of(first,
                snapshot("second", checkpoint, Map.of(0, 11L)))));
        assertThrows(IllegalArgumentException.class, () -> store.saveAll(List.of(first, first)));
        org.mockito.Mockito.verifyNoInteractions(repository);
    }

    private static DetectionStateSnapshotEntity row(Instant time, String offsets) {
        var row = new DetectionStateSnapshotEntity();
        row.setSnapshotTimestamp(time);
        row.setPartitionOffsetsJson(offsets);
        return row;
    }

    private static DetectionStateSnapshot snapshot(String id, Instant time, Map<Integer, Long> offsets) {
        return new DetectionStateSnapshot(id, "v1", "tenant-a", 0, 10L, new byte[]{1}, time, offsets);
    }
}
