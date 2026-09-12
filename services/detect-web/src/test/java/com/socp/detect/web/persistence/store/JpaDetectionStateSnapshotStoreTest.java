package com.socp.detect.web.persistence.store;

import com.socp.detect.web.persistence.entity.DetectionStateSnapshotEntity;
import com.socp.detect.web.persistence.entity.DetectionStateOwnerEntity;
import com.socp.detect.web.persistence.repository.DetectionStateOwnerRepository;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class JpaDetectionStateSnapshotStoreTest {

    @Mock
    private DetectionStateSnapshotRepository repository;

    @Mock
    private DetectionStateOwnerRepository ownerRepository;

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
    void persistsTheInputTopicWithAStateCheckpoint() throws Exception {
        DetectionStateSnapshot snapshot = new DetectionStateSnapshot(
                "rule-topic", "v1", "tenant-a", 0, 9L, new byte[]{4},
                Instant.parse("2026-09-10T12:00:00Z"), Map.of(1, 9L), Map.of(), "events-v2");
        when(repository.findByTenantIdAndRuleIdAndShardId("tenant-a", "rule-topic", 0))
                .thenReturn(Optional.empty());

        JpaDetectionStateSnapshotStore store = new JpaDetectionStateSnapshotStore(
                repository, ownerRepository, "events-v2");
        store.save(snapshot);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<DetectionStateSnapshotEntity>> captor =
                (ArgumentCaptor<List<DetectionStateSnapshotEntity>>) (ArgumentCaptor<?>) ArgumentCaptor.forClass(List.class);
        verify(repository).saveAllAndFlush(captor.capture());
        assertEquals("events-v2", captor.getValue().getFirst().getInputTopic());

        when(repository.findByTenantIdAndRuleIdAndShardId("tenant-a", "rule-topic", 0))
                .thenReturn(Optional.of(captor.getValue().getFirst()));
        assertEquals("events-v2", store.latest("tenant-a", "rule-topic", 0).orElseThrow().inputTopic());
    }

    @Test
    void rejectsAStateCheckpointFromAnotherInputTopic() {
        DetectionStateSnapshot snapshot = new DetectionStateSnapshot(
                "rule-topic", "v1", "tenant-a", 0, 9L, new byte[]{4},
                Instant.parse("2026-09-10T12:00:00Z"), Map.of(1, 9L), Map.of(), "events-old");
        JpaDetectionStateSnapshotStore store = new JpaDetectionStateSnapshotStore(
                repository, ownerRepository, "events-new");

        assertThrows(IllegalArgumentException.class, () -> store.save(snapshot));
        org.mockito.Mockito.verifyNoInteractions(repository, ownerRepository);
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

    @Test
    void persistsAndRestoresOwnerFenceVector() throws Exception {
        String ownerKey = DetectionStateOwnership.unitKey("socp-events", 0, 2);
        DetectionStateOwnerEntity owner = new DetectionStateOwnerEntity(
                ownerKey, "socp-events", 0, 2, "node-a", 7L,
                Instant.now().plusSeconds(30), Instant.now());
        when(ownerRepository.findByOwnerKeyForUpdate(anyString())).thenReturn(Optional.of(owner));
        when(repository.findByTenantIdAndRuleIdAndShardId("tenant-a", "rule-1", 2))
                .thenReturn(Optional.empty());
        DetectionStateSnapshot snapshot = new DetectionStateSnapshot(
                "rule-1", "v3", "tenant-a", 2, 14L, new byte[]{1, 2, 3},
                Instant.parse("2026-09-10T12:00:00Z"), Map.of(0, 14L), Map.of(0, 7L));

        JpaDetectionStateSnapshotStore store = new JpaDetectionStateSnapshotStore(
                repository, ownerRepository, "socp-events");
        store.save(snapshot);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<DetectionStateSnapshotEntity>> captor =
                (ArgumentCaptor<List<DetectionStateSnapshotEntity>>) (ArgumentCaptor<?>) ArgumentCaptor.forClass(List.class);
        verify(repository).saveAllAndFlush(captor.capture());
        DetectionStateSnapshotEntity persisted = captor.getValue().getFirst();
        assertEquals(Map.of("0", 7L), com.socp.rule.util.Json.mapper().readValue(
                persisted.getPartitionOwnerEpochsJson(),
                new com.fasterxml.jackson.core.type.TypeReference<Map<String, Long>>() {}));

        when(repository.findByTenantIdAndRuleIdAndShardId("tenant-a", "rule-1", 2))
                .thenReturn(Optional.of(persisted));
        assertEquals(Map.of(0, 7L),
                store.latest("tenant-a", "rule-1", 2).orElseThrow().partitionOwnerEpochs());
    }

    @Test
    void rejectsSupersededOwnerFenceBeforeLoadingSnapshotRows() {
        String ownerKey = DetectionStateOwnership.unitKey("socp-events", 0, 0);
        when(ownerRepository.findByOwnerKeyForUpdate(ownerKey)).thenReturn(Optional.of(
                new DetectionStateOwnerEntity(ownerKey, "socp-events", 0, 0, "node-b", 8L,
                        Instant.now().plusSeconds(30), Instant.now())));
        DetectionStateSnapshot stale = new DetectionStateSnapshot(
                "rule-1", "v1", "tenant-a", 0, 10L, new byte[]{1},
                Instant.parse("2026-09-10T12:00:00Z"), Map.of(0, 10L), Map.of(0, 7L));

        JpaDetectionStateSnapshotStore store = new JpaDetectionStateSnapshotStore(
                repository, ownerRepository, "socp-events");
        assertThrows(DetectionStateOwnership.StaleStateOwnerException.class,
                () -> store.save(stale));
        org.mockito.Mockito.verifyNoInteractions(repository);
    }

    @Test
    void rejectsSnapshotWhenOwnerFenceRowIsMissing() {
        String ownerKey = DetectionStateOwnership.unitKey("socp-events", 0, 0);
        when(ownerRepository.findByOwnerKeyForUpdate(ownerKey)).thenReturn(Optional.empty());

        DetectionStateSnapshot snapshot = snapshot(Map.of(0, 17L), Map.of(0, 7L));
        JpaDetectionStateSnapshotStore store = new JpaDetectionStateSnapshotStore(
                repository, ownerRepository, "socp-events");

        assertThrows(DetectionStateOwnership.StaleStateOwnerException.class,
                () -> store.saveAll(List.of(snapshot)));
        verify(repository, org.mockito.Mockito.never()).saveAllAndFlush(any());
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

    private static DetectionStateSnapshot snapshot(Map<Integer, Long> offsets, Map<Integer, Long> ownerEpochs) {
        return new DetectionStateSnapshot("rule-1", "v1", "tenant-a", 0, 10L, new byte[]{1},
                Instant.parse("2026-09-10T12:00:00Z"), offsets, ownerEpochs);
    }
}
