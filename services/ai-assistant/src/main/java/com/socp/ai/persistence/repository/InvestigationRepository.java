package com.socp.ai.persistence.repository;

import com.socp.ai.persistence.entity.InvestigationEntity;
import org.springframework.data.jpa.repository.Modifying;
import com.socp.platform.tenant.persistence.TenantScopedRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface InvestigationRepository extends TenantScopedRepository<InvestigationEntity, String> {
    List<InvestigationEntity> findByTenantId(String tenantId);
    Optional<InvestigationEntity> findByIdAndTenantId(String id, String tenantId);
    Optional<InvestigationEntity> findByTenantIdAndAlertId(String tenantId, String alertId);

    @Modifying
    @Transactional
    @Query(value = "insert into t_ai_investigation "
            + "(id, tenant_id, alert_id, status, result_json, created_at, updated_at) "
            + "values (:id, :tenant, :alert, 'NEW', '{}', :now, :now)", nativeQuery = true)
    int insertReceipt(@Param("id") String id, @Param("tenant") String tenant,
                      @Param("alert") String alert, @Param("now") Instant now);

    @Modifying
    @Transactional
    @Query("update InvestigationEntity i set i.status = 'NEW', i.resultJson = '{}', i.updatedAt = :now "
            + "where i.id = :id and i.tenantId = :tenant and i.status = 'FAILED'")
    int requeueFailed(@Param("id") String id, @Param("tenant") String tenant, @Param("now") Instant now);

    @Query("select i from InvestigationEntity i where i.status = 'NEW' "
            + "or (i.status = 'RUNNING' and (i.claimUntil is null or i.claimUntil < :now)) "
            + "order by i.updatedAt asc, i.id asc")
    List<InvestigationEntity> findRecoverable(@Param("now") Instant now,
                                            org.springframework.data.domain.Pageable pageable);

    @Modifying
    @Transactional
    @Query("update InvestigationEntity i set i.status = 'RUNNING', i.claimOwner = :owner, "
            + "i.claimUntil = :claimUntil, i.updatedAt = :now "
            + "where i.id = :id and i.tenantId = :tenant "
            + "and i.status not in ('COMPLETED', 'PARTIAL') "
            + "and (i.status <> 'RUNNING' or i.claimUntil is null or i.claimUntil < :now)")
    int claim(@Param("id") String id, @Param("tenant") String tenant, @Param("owner") String owner,
              @Param("now") Instant now, @Param("claimUntil") Instant claimUntil);

    @Modifying
    @Transactional
    @Query("update InvestigationEntity i set i.status = :status, i.resultJson = :resultJson, "
            + "i.claimOwner = null, i.claimUntil = null, i.updatedAt = :now "
            + "where i.id = :id and i.tenantId = :tenant and i.claimOwner = :owner")
    int complete(@Param("id") String id, @Param("tenant") String tenant, @Param("owner") String owner,
                 @Param("status") String status, @Param("resultJson") String resultJson,
                 @Param("now") Instant now);

    @Modifying
    @Transactional
    @Query("update InvestigationEntity i set i.status = 'FAILED', i.resultJson = :resultJson, "
            + "i.claimOwner = null, i.claimUntil = null, i.updatedAt = :now "
            + "where i.id = :id and i.tenantId = :tenant and i.claimOwner = :owner")
    int fail(@Param("id") String id, @Param("tenant") String tenant, @Param("owner") String owner,
             @Param("resultJson") String resultJson, @Param("now") Instant now);

    @Modifying
    @Transactional
    @Query("update InvestigationEntity i set i.incidentId = :incidentId, i.appendedAt = :appendedAt, "
            + "i.resultJson = :resultJson, i.updatedAt = :now "
            + "where i.id = :id and i.tenantId = :tenant and i.appendedAt is null")
    int markAppended(@Param("id") String id, @Param("tenant") String tenant,
                     @Param("incidentId") String incidentId, @Param("appendedAt") Instant appendedAt,
                     @Param("resultJson") String resultJson, @Param("now") Instant now);
}
