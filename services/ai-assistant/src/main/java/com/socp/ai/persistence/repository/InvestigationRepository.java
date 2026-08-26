package com.socp.ai.persistence.repository;

import com.socp.ai.persistence.entity.InvestigationEntity;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

public interface InvestigationRepository extends JpaRepository<InvestigationEntity, String> {
    Optional<InvestigationEntity> findByIdAndTenantId(String id, String tenantId);
    Optional<InvestigationEntity> findByTenantIdAndAlertId(String tenantId, String alertId);

    @Modifying
    @Transactional
    @Query("update InvestigationEntity i set i.status = 'RUNNING', i.claimOwner = :owner, "
            + "i.claimUntil = :claimUntil, i.updatedAt = :now "
            + "where i.id = :id and i.tenantId = :tenant "
            + "and i.status <> 'COMPLETED' "
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
