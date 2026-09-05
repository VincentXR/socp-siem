package com.socp.soar.web.persistence.repository;

import com.socp.platform.tenant.persistence.TenantScopedRepository;
import com.socp.soar.web.persistence.entity.SoarSignalOutboxEntity;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface SoarSignalOutboxRepository extends TenantScopedRepository<SoarSignalOutboxEntity, String> {
    Optional<SoarSignalOutboxEntity> findByTenantIdAndId(String tenantId, String id);
    List<SoarSignalOutboxEntity> findTop100ByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
            String status, Instant now);
    Optional<SoarSignalOutboxEntity> findByTenantIdAndRunIdAndSignalType(
            String tenantId, String runId, String signalType);
    Optional<SoarSignalOutboxEntity> findByTenantIdAndRunIdAndSignalTypeAndSignalKey(
            String tenantId, String runId, String signalType, String signalKey);
    long countByStatus(String status);
    long countByTenantIdAndStatus(String tenantId, String status);
    @Modifying
    @Transactional
    @Query("update SoarSignalOutboxEntity s set s.status = 'SENDING', s.claimedBy = :worker, "
            + "s.claimedAt = :now, s.updatedAt = :now where s.tenantId = :tenantId and s.id = :id "
            + "and s.status = 'PENDING' "
            + "and s.nextAttemptAt <= :now")
    int claim(@Param("tenantId") String tenantId, @Param("id") String id,
              @Param("worker") String worker, @Param("now") Instant now);
    @Modifying
    @Transactional
    @Query("update SoarSignalOutboxEntity s set s.status = 'PENDING', s.claimedBy = null, "
            + "s.claimedAt = null, s.nextAttemptAt = :now, s.updatedAt = :now "
            + "where s.status = 'SENDING' and s.claimedAt < :staleBefore")
    int recoverStaleClaims(@Param("staleBefore") Instant staleBefore, @Param("now") Instant now);
    List<SoarSignalOutboxEntity> findByTenantIdAndStatusOrderByUpdatedAtAsc(String tenantId, String status);
}
