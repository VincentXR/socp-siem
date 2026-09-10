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
}
