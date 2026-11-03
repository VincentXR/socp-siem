package com.socp.detect.web.persistence.repository;

import com.socp.detect.web.persistence.entity.DetectionCheckpointMetadata;
import com.socp.detect.web.persistence.entity.DetectionStateSnapshotEntity;
import com.socp.platform.tenant.persistence.TenantScopedRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

public interface DetectionStateSnapshotRepository
        extends TenantScopedRepository<DetectionStateSnapshotEntity, String> {

    Optional<DetectionStateSnapshotEntity> findByTenantIdAndRuleIdAndShardIdAndInputTopic(
            String tenantId, String ruleId, int shardId, String inputTopic);

    @Query("select new com.socp.detect.web.persistence.entity.DetectionCheckpointMetadata(s.id, s.snapshotTimestamp, "
            + "s.partitionOffsetsJson, s.rowVersion) "
            + "from DetectionStateSnapshotEntity s where s.tenantId=:tenantId "
            + "and s.ruleId=:ruleId and s.shardId=:shardId and s.inputTopic=:inputTopic")
    Optional<DetectionCheckpointMetadata> findMetadataByTenantIdAndRuleIdAndShardIdAndInputTopic(
            @Param("tenantId") String tenantId, @Param("ruleId") String ruleId,
            @Param("shardId") int shardId, @Param("inputTopic") String inputTopic);

    /** A zero-row receipt must abort the entire checkpoint generation. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional(propagation = Propagation.MANDATORY)
    @Query("update DetectionStateSnapshotEntity s set s.ruleVersion=:#{#replacement.ruleVersion}, "
            + "s.lastProcessedOffset=:#{#replacement.lastProcessedOffset}, "
            + "s.serializedState=:#{#replacement.serializedState}, "
            + "s.snapshotTimestamp=:#{#replacement.snapshotTimestamp}, "
            + "s.partitionOffsetsJson=:#{#replacement.partitionOffsetsJson}, "
            + "s.partitionOwnerEpochsJson=:#{#replacement.partitionOwnerEpochsJson}, "
            + "s.rowVersion=s.rowVersion+1 where s.tenantId=:tenantId "
            + "and s.id=:#{#replacement.id} and s.rowVersion=:#{#replacement.rowVersion}")
    int replaceByTenantIdAndId(@Param("tenantId") String tenantId,
                              @Param("replacement") DetectionStateSnapshotEntity replacement);
}
