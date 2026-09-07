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

    /**
     * Cross-instance admission lock for event evaluation.  The rule rows are
     * locked for the duration of the evaluation transaction, which makes the
     * receipt check, capacity check and run insert one database-serialized
     * decision instead of a JVM-local read-then-insert race.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from SoarAutomationRuleEntity r "
            + "where r.tenantId = :tenantId and r.enabled = true "
            + "order by r.priority asc, r.id asc")
    List<SoarAutomationRuleEntity> findByTenantIdAndEnabledTrueOrderByPriorityAscForUpdate(
            @Param("tenantId") String tenantId);
}
