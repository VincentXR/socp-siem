package com.socp.detect.web.persistence.store;

import com.socp.detect.web.persistence.entity.DetectionStateSnapshotEntity;
import com.socp.detect.web.persistence.repository.DetectionStateSnapshotRepository;
import com.socp.rule.state.DetectionStateSnapshot;
import com.socp.rule.state.DetectionStateSnapshotStore;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Base64;
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
    @Transactional
    public void save(DetectionStateSnapshot snapshot) {
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
                && row.getSnapshotTimestamp().isAfter(snapshot.snapshotTimestamp())) return;
        row.setRuleVersion(snapshot.ruleVersion());
        row.setLastProcessedOffset(snapshot.lastProcessedOffset());
        row.setSerializedState(Base64.getEncoder().encodeToString(snapshot.serializedState()));
        row.setSnapshotTimestamp(snapshot.snapshotTimestamp());
        repository.saveAndFlush(row);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<DetectionStateSnapshot> latest(String tenantId, String ruleId, int shardId) {
        return repository.findByTenantIdAndRuleIdAndShardId(tenantId, ruleId, shardId)
                .map(row -> new DetectionStateSnapshot(row.getRuleId(), row.getRuleVersion(), row.getTenantId(),
                        row.getShardId(), row.getLastProcessedOffset(), decode(row.getSerializedState()),
                        row.getSnapshotTimestamp()));
    }

    private static byte[] decode(String value) {
        try {
            return value == null ? new byte[0] : Base64.getDecoder().decode(value);
        } catch (IllegalArgumentException failure) {
            throw new IllegalStateException("invalid persisted detection snapshot", failure);
        }
    }
}
