package com.socp.detect.web.persistence.store;

import com.socp.detect.web.persistence.entity.DetectionStateSnapshotEntity;
import com.socp.detect.web.persistence.repository.DetectionStateSnapshotRepository;
import com.socp.rule.state.DetectionStateSnapshot;
import com.socp.rule.state.DetectionStateSnapshotStore;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** PostgreSQL/H2 implementation of the versioned detection-state SPI. */
@Component
public class JpaDetectionStateSnapshotStore implements DetectionStateSnapshotStore {

    private final DetectionStateSnapshotRepository repository;

    public JpaDetectionStateSnapshotStore(DetectionStateSnapshotRepository repository) {
        this.repository = repository;
    }

    @Override
    public boolean supportsAtomicBatch() {
        return true;
    }

    @Override
    @Transactional
    public void save(DetectionStateSnapshot snapshot) {
        if (snapshot == null) throw new IllegalArgumentException("snapshot is required");
        saveAll(List.of(snapshot));
    }

    /** Save a checkpoint generation in one transaction so mixed rule rows are not visible. */
    @Override
    @Transactional
    public void saveAll(List<DetectionStateSnapshot> snapshots) {
        if (snapshots == null || snapshots.isEmpty()) return;
        List<DetectionStateSnapshotEntity> rows = new ArrayList<>(snapshots.size());
        for (DetectionStateSnapshot snapshot : snapshots) {
            if (snapshot == null) throw new IllegalArgumentException("snapshot is required");
            DetectionStateSnapshotEntity row = repository
                    .findByTenantIdAndRuleIdAndShardId(snapshot.tenantId(), snapshot.ruleId(), snapshot.shardId())
                    .orElseGet(() -> {
                        DetectionStateSnapshotEntity created = new DetectionStateSnapshotEntity();
                        created.setId(UUID.randomUUID().toString());
                        created.setTenantId(snapshot.tenantId());
                        created.setRuleId(snapshot.ruleId());
                        created.setShardId(snapshot.shardId());
                        return created;
                    });
            if (row.getSnapshotTimestamp() != null
                    && row.getSnapshotTimestamp().isAfter(snapshot.snapshotTimestamp())) continue;
            row.setRuleVersion(snapshot.ruleVersion());
            row.setLastProcessedOffset(snapshot.lastProcessedOffset());
            row.setSerializedState(Base64.getEncoder().encodeToString(snapshot.serializedState()));
            row.setSnapshotTimestamp(snapshot.snapshotTimestamp());
            try {
                row.setPartitionOffsetsJson(com.socp.rule.util.Json.mapper()
                        .writeValueAsString(snapshot.partitionOffsets()));
            } catch (Exception failure) {
                throw new IllegalStateException("invalid detection checkpoint offsets", failure);
            }
            rows.add(row);
        }
        if (!rows.isEmpty()) repository.saveAllAndFlush(rows);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<DetectionStateSnapshot> latest(String tenantId, String ruleId, int shardId) {
        return repository.findByTenantIdAndRuleIdAndShardId(tenantId, ruleId, shardId)
                .map(row -> new DetectionStateSnapshot(row.getRuleId(), row.getRuleVersion(), row.getTenantId(),
                        row.getShardId(), row.getLastProcessedOffset(), decode(row.getSerializedState()),
                        row.getSnapshotTimestamp(), decodeOffsets(row.getPartitionOffsetsJson())));
    }

    private static byte[] decode(String value) {
        try {
            return value == null ? new byte[0] : Base64.getDecoder().decode(value);
        } catch (IllegalArgumentException failure) {
            throw new IllegalStateException("invalid persisted detection snapshot", failure);
        }
    }

    private static Map<Integer, Long> decodeOffsets(String value) {
        if (value == null || value.isBlank()) return Map.of();
        try {
            Map<String, Long> raw = com.socp.rule.util.Json.mapper().readValue(value,
                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, Long>>() {});
            Map<Integer, Long> offsets = new LinkedHashMap<>();
            raw.forEach((partition, offset) -> {
                try {
                    offsets.put(Integer.valueOf(partition), offset);
                } catch (NumberFormatException failure) {
                    throw new IllegalArgumentException("invalid checkpoint partition " + partition, failure);
                }
            });
            return offsets;
        } catch (Exception failure) {
            throw new IllegalStateException("invalid persisted detection checkpoint offsets", failure);
        }
    }
}
