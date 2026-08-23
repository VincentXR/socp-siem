package com.socp.detect.web.ueba;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

interface EntityRiskProfileRepository extends JpaRepository<EntityRiskProfileEntity, String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from EntityRiskProfileEntity p where p.tenantId = :tenantId and p.entity = :entity")
    Optional<EntityRiskProfileEntity> findForUpdate(@Param("tenantId") String tenantId,
                                                    @Param("entity") String entity);

    Optional<EntityRiskProfileEntity> findByTenantIdAndEntity(String tenantId, String entity);

    java.util.List<EntityRiskProfileEntity> findByTenantId(String tenantId);
}
