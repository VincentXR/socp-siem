package com.socp.soar.web.persistence.repository;

import com.socp.platform.tenant.persistence.TenantScopedRepository;
import com.socp.soar.web.persistence.entity.SoarAutomationRuleEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface SoarAutomationRuleRepository extends TenantScopedRepository<SoarAutomationRuleEntity, String> {
    Optional<SoarAutomationRuleEntity> findByTenantIdAndId(String tenantId, String id);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from SoarAutomationRuleEntity r where r.tenantId = :tenantId and r.id = :id")
    Optional<SoarAutomationRuleEntity> findByTenantIdAndIdForUpdate(@Param("tenantId") String tenantId,
                                                                      @Param("id") String id);
    List<SoarAutomationRuleEntity> findByTenantIdOrderByPriorityAscUpdatedAtDesc(String tenantId);
    Page<SoarAutomationRuleEntity> findByTenantIdOrderByPriorityAscUpdatedAtDesc(String tenantId,
                                                                                  Pageable pageable);
    List<SoarAutomationRuleEntity> findByTenantIdAndEnabledTrueOrderByPriorityAsc(String tenantId);
}
