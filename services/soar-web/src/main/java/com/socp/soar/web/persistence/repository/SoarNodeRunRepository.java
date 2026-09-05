package com.socp.soar.web.persistence.repository;

import com.socp.platform.tenant.persistence.TenantScopedRepository;
import com.socp.soar.web.persistence.entity.SoarNodeRunEntity;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import java.util.List;
import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SoarNodeRunRepository extends TenantScopedRepository<SoarNodeRunEntity, String> {
    List<SoarNodeRunEntity> findByTenantIdAndRunIdOrderByUpdatedAtAsc(String tenantId, String runId);
    Page<SoarNodeRunEntity> findByTenantIdAndRunIdOrderByUpdatedAtAsc(String tenantId, String runId,
                                                                       Pageable pageable);
    Optional<SoarNodeRunEntity> findByTenantIdAndId(String tenantId, String id);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select n from SoarNodeRunEntity n where n.tenantId = :tenantId and n.id = :id")
    Optional<SoarNodeRunEntity> findByTenantIdAndIdForUpdate(@Param("tenantId") String tenantId,
                                                              @Param("id") String id);
    Optional<SoarNodeRunEntity> findByTenantIdAndRunIdAndNodeIdAndIterationPath(
            String tenantId, String runId, String nodeId, String iterationPath);
}
