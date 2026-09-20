package com.socp.detect.web.persistence.repository;

import com.socp.detect.web.persistence.entity.DetectionRouteSourceEntity;
import com.socp.platform.tenant.persistence.TenantScopedRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface DetectionRouteSourceRepository
        extends TenantScopedRepository<DetectionRouteSourceEntity, String> {

    Optional<DetectionRouteSourceEntity>
    findByTenantIdAndSourceTopicAndSourcePartitionAndSourceOffset(
            String tenantId, String sourceTopic, int sourcePartition, long sourceOffset);

    Optional<DetectionRouteSourceEntity>
    findFirstByTenantIdAndSourceEventIdAndStatusInOrderByCreatedAtAsc(
            String tenantId, String sourceEventId, List<String> statuses);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query(value = "delete from t_detection_route_source where id in ("
            + "select id from t_detection_route_source "
            + "where status in ('ROUTED','DUPLICATE') and created_at < :cutoff "
            + "order by created_at asc limit :batchSize)", nativeQuery = true)
    int deleteRoutedBatchBefore(@Param("cutoff") Instant cutoff,
                                @Param("batchSize") int batchSize);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query(value = "delete from t_detection_route_source where id in ("
            + "select id from t_detection_route_source "
            + "where status = 'DEAD' and created_at < :cutoff "
            + "order by created_at asc limit :batchSize)", nativeQuery = true)
    int deleteDeadBatchBefore(@Param("cutoff") Instant cutoff,
                              @Param("batchSize") int batchSize);
}
