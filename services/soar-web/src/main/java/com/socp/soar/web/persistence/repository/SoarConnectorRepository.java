package com.socp.soar.web.persistence.repository;

import com.socp.platform.tenant.persistence.TenantScopedRepository;
import com.socp.soar.web.persistence.entity.SoarConnectorEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface SoarConnectorRepository extends TenantScopedRepository<SoarConnectorEntity, String> {
    Optional<SoarConnectorEntity> findByTenantIdAndId(String tenantId, String id);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from SoarConnectorEntity c where c.tenantId = :tenantId and c.id = :id")
    Optional<SoarConnectorEntity> findByTenantIdAndIdForUpdate(@Param("tenantId") String tenantId,
                                                                @Param("id") String id);
    List<SoarConnectorEntity> findByTenantIdOrderByNameAsc(String tenantId);
    Page<SoarConnectorEntity> findByTenantIdOrderByNameAsc(String tenantId, Pageable pageable);
}
