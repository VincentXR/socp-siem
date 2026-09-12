package com.socp.detect.web.persistence.store;

import com.socp.detect.web.persistence.entity.DetectionStateSnapshotEntity;
import com.socp.detect.web.persistence.entity.DetectionStateOwnerEntity;
import com.socp.detect.web.persistence.repository.DetectionStateOwnerRepository;
import com.socp.detect.web.persistence.repository.DetectionStateSnapshotRepository;
import com.socp.rule.state.DetectionStateSnapshot;
import com.socp.rule.state.DetectionStateSnapshotStore;
import org.springframework.beans.factory.annotation.Value;
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
    private final DetectionStateOwnerRepository ownerRepository;
    private final String inputTopic;

    /** Compatibility constructor for focused tests and direct callers. */
    public JpaDetectionStateSnapshotStore(DetectionStateSnapshotRepository repository) {
        this(repository, null, "socp-events");
    }

    @org.springframework.beans.factory.annotation.Autowired
    public JpaDetectionStateSnapshotStore(DetectionStateSnapshotRepository repository,
                                         DetectionStateOwnerRepository ownerRepository,
                                         @Value("${socp.kafka.topic:socp-events}") String inputTopic) {
        this.repository = repository;
        this.ownerRepository = ownerRepository;
        this.inputTopic = inputTopic == null || inputTopic.isBlank() ? "socp-events" : inputTopic;
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
        DetectionCheckpointPolicy.validateGeneration(snapshots);
        for (DetectionStateSnapshot snapshot : snapshots) validateInputTopic(snapshot);
        assertOwnerFences(snapshots);
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
            if (!DetectionCheckpointPolicy.canReplace(row.getSnapshotTimestamp(),
                    decodeOffsets(row.getPartitionOffsetsJson()), snapshot)) return;
            rows.add(row);
        }
        // Validate every row before mutating any managed entity: returning
        // early after a mutation would still let Hibernate dirty checking
        // flush half a generation at transaction completion.
        for (int index = 0; index < snapshots.size(); index++) {
            DetectionStateSnapshot snapshot = snapshots.get(index);
            DetectionStateSnapshotEntity row = rows.get(index);
            row.setRuleVersion(snapshot.ruleVersion());
            row.setLastProcessedOffset(snapshot.lastProcessedOffset());
            row.setSerializedState(Base64.getEncoder().encodeToString(snapshot.serializedState()));
            row.setSnapshotTimestamp(snapshot.snapshotTimestamp());
            row.setInputTopic(snapshot.inputTopic() == null ? inputTopic : snapshot.inputTopic());
            try {
                row.setPartitionOffsetsJson(com.socp.rule.util.Json.mapper()
                        .writeValueAsString(snapshot.partitionOffsets()));
                row.setPartitionOwnerEpochsJson(com.socp.rule.util.Json.mapper()
                        .writeValueAsString(snapshot.partitionOwnerEpochs()));
            } catch (Exception failure) {
                throw new IllegalStateException("invalid detection checkpoint offsets", failure);
            }
        }
        if (!rows.isEmpty()) repository.saveAllAndFlush(rows);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<DetectionStateSnapshot> latest(String tenantId, String ruleId, int shardId) {
        return repository.findByTenantIdAndRuleIdAndShardId(tenantId, ruleId, shardId)
                .map(row -> new DetectionStateSnapshot(row.getRuleId(), row.getRuleVersion(), row.getTenantId(),
                        row.getShardId(), row.getLastProcessedOffset(), decode(row.getSerializedState()),
                        row.getSnapshotTimestamp(), decodeOffsets(row.getPartitionOffsetsJson()),
                        decodeOffsets(row.getPartitionOwnerEpochsJson()), row.getInputTopic()));
    }

    private void validateInputTopic(DetectionStateSnapshot snapshot) {
        if (snapshot == null) throw new IllegalArgumentException("snapshot is required");
        if (snapshot.inputTopic() != null && !inputTopic.equals(snapshot.inputTopic())) {
            throw new IllegalArgumentException("snapshot input topic does not match configured topic: "
                    + snapshot.inputTopic());
        }
    }

    private void assertOwnerFences(List<DetectionStateSnapshot> snapshots) {
        if (ownerRepository == null) return;
        for (DetectionStateSnapshot snapshot : snapshots) {
            for (Map.Entry<Integer, Long> entry : snapshot.partitionOwnerEpochs().entrySet()) {
                String topic = snapshot.inputTopic() == null ? inputTopic : snapshot.inputTopic();
                String ownerKey = DetectionStateOwnership.unitKey(
                        topic, entry.getKey(), snapshot.shardId());
                DetectionStateOwnerEntity owner = ownerRepository.findByOwnerKeyForUpdate(ownerKey)
                        .orElseThrow(() -> new DetectionStateOwnership.StaleStateOwnerException(
                                "checkpoint owner fence is missing: " + ownerKey));
                if (owner.getFencingEpoch() != entry.getValue()) {
                    throw new DetectionStateOwnership.StaleStateOwnerException(
                            "checkpoint owner fence was superseded: " + ownerKey
                                    + " expected=" + entry.getValue()
                                    + " actual=" + owner.getFencingEpoch());
                }
            }
        }
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
