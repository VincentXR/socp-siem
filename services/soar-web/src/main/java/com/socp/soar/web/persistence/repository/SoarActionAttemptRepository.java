package com.socp.soar.web.persistence.repository;

import com.socp.platform.tenant.persistence.TenantScopedRepository;
import com.socp.soar.web.persistence.entity.SoarActionAttemptEntity;

import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

public interface SoarActionAttemptRepository extends TenantScopedRepository<SoarActionAttemptEntity, String> {
    List<SoarActionAttemptEntity> findByTenantIdAndNodeRunIdOrderByAttemptNoAsc(String tenantId, String nodeRunId);
    Page<SoarActionAttemptEntity> findByTenantIdAndNodeRunIdOrderByAttemptNoAsc(
            String tenantId, String nodeRunId, Pageable pageable);
    Optional<SoarActionAttemptEntity> findByTenantIdAndNodeRunIdAndAttemptNo(
            String tenantId, String nodeRunId, int attemptNo);

    /** Serialize Activity redelivery against the attempt's business key. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from SoarActionAttemptEntity a where a.tenantId = :tenantId "
            + "and a.nodeRunId = :nodeRunId and a.attemptNo = :attemptNo")
    Optional<SoarActionAttemptEntity> findByTenantIdAndNodeRunIdAndAttemptNoForUpdate(
            @Param("tenantId") String tenantId, @Param("nodeRunId") String nodeRunId,
            @Param("attemptNo") int attemptNo);
}
