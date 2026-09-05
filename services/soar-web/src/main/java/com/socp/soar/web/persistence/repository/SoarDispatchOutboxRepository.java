package com.socp.soar.web.persistence.repository;

import com.socp.platform.tenant.persistence.TenantScopedRepository;
import com.socp.soar.web.persistence.entity.SoarDispatchOutboxEntity;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface SoarDispatchOutboxRepository extends TenantScopedRepository<SoarDispatchOutboxEntity, String> {
    Optional<SoarDispatchOutboxEntity> findByTenantIdAndId(String tenantId, String id);
    Optional<SoarDispatchOutboxEntity> findByTenantIdAndRunId(String tenantId, String runId);
    List<SoarDispatchOutboxEntity> findTop100ByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
            String status, Instant now);
    long countByStatus(String status);
    long countByTenantIdAndStatusAndNextAttemptAtLessThanEqual(String tenantId, String status, Instant now);
    @Modifying
    @Transactional
    @Query("update SoarDispatchOutboxEntity o set o.status = 'DISPATCHING', o.claimedBy = :worker, "
            + "o.claimedAt = :now, o.updatedAt = :now where o.tenantId = :tenantId and o.id = :id "
            + "and o.status = 'PENDING' "
            + "and o.nextAttemptAt <= :now")
    int claim(@Param("tenantId") String tenantId, @Param("id") String id,
              @Param("worker") String worker, @Param("now") Instant now);
    @Modifying
    @Transactional
    @Query("update SoarDispatchOutboxEntity o set o.status = 'PENDING', o.claimedBy = null, "
            + "o.claimedAt = null, o.nextAttemptAt = :now, o.updatedAt = :now "
            + "where o.status = 'DISPATCHING' and o.claimedAt < :staleBefore")
    int recoverStaleClaims(@Param("staleBefore") Instant staleBefore, @Param("now") Instant now);
    List<SoarDispatchOutboxEntity> findByTenantIdAndStatusOrderByUpdatedAtAsc(String tenantId, String status);
}
