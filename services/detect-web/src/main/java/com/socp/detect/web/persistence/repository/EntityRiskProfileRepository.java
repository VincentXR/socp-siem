package com.socp.detect.web.persistence.repository;

import com.socp.detect.web.persistence.entity.EntityRiskProfileEntity;

import jakarta.persistence.LockModeType;
import com.socp.platform.tenant.persistence.TenantScopedRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.List;

public interface EntityRiskProfileRepository extends TenantScopedRepository<EntityRiskProfileEntity, String> {
    List<EntityRiskProfileEntity> findByTenantId(String tenantId);
    Optional<EntityRiskProfileEntity> findByStorageIdAndTenantId(String storageId, String tenantId);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from EntityRiskProfileEntity p where p.tenantId = :tenantId and p.entity = :entity")
    Optional<EntityRiskProfileEntity> findForUpdate(@Param("tenantId") String tenantId,
                                                    @Param("entity") String entity);

    Optional<EntityRiskProfileEntity> findByTenantIdAndEntity(String tenantId, String entity);

}
