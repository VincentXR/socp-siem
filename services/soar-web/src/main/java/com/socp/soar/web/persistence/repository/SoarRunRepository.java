package com.socp.soar.web.persistence.repository;

import com.socp.platform.tenant.persistence.TenantScopedRepository;
import com.socp.soar.web.persistence.entity.SoarRunEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.List;
import java.util.Collection;
import java.time.Instant;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Modifying;

public interface SoarRunRepository extends TenantScopedRepository<SoarRunEntity, String> {
    Optional<SoarRunEntity> findByTenantIdAndId(String tenantId, String id);
    /** Serialize append-only timeline sequence allocation for a run. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from SoarRunEntity r where r.tenantId = :tenantId and r.id = :id")
    Optional<SoarRunEntity> findByTenantIdAndIdForUpdate(String tenantId, String id);
    Optional<SoarRunEntity> findByTenantIdAndRequestId(String tenantId, String requestId);
    Page<SoarRunEntity> findByTenantIdOrderByCreatedAtDesc(String tenantId, Pageable pageable);
    @Query("select r from SoarRunEntity r "
            + "where r.tenantId = :tenantId "
            + "and (:status is null or upper(r.status) = upper(:status)) "
            + "and (:playbookVersionId is null or r.playbookVersionId = :playbookVersionId) "
            + "and (:triggerType is null or upper(r.triggerType) = upper(:triggerType)) "
            + "and (:requestedBy is null or lower(r.requestedBy) = lower(:requestedBy)) "
            + "and (:createdFrom is null or r.createdAt >= :createdFrom) "
            + "and (:createdTo is null or r.createdAt < :createdTo) "
            + "order by r.createdAt desc")
    Page<SoarRunEntity> searchByTenant(@Param("tenantId") String tenantId,
                                       @Param("status") String status,
                                       @Param("playbookVersionId") String playbookVersionId,
                                       @Param("triggerType") String triggerType,
                                       @Param("requestedBy") String requestedBy,
                                       @Param("createdFrom") Instant createdFrom,
                                       @Param("createdTo") Instant createdTo,
                                       Pageable pageable);
    List<SoarRunEntity> findTop100ByStatusOrderByUpdatedAtAsc(String status);
    List<SoarRunEntity> findTop100ByStatusInAndUpdatedAtBeforeOrderByUpdatedAtAsc(
            Collection<String> statuses, Instant updatedBefore);
    long countByTenantIdAndPlaybookVersionIdAndStatusIn(String tenantId, String playbookVersionId,
                                                        Collection<String> statuses);
    long countByTenantIdAndPlaybookVersionIdInAndStatusIn(String tenantId, Collection<String> playbookVersionIds,
                                                          Collection<String> statuses);
    long countByTenantIdAndStatus(String tenantId, String status);
    /** System-scope aggregate used only for low-cardinality metrics/health. */
    long countByStatus(String status);

    /** System-scope retention purge; call only from SoarRunRetentionWorker. */
    @Modifying
    @Query("delete from SoarRunEntity r where r.id in :ids")
    int deleteByIds(@Param("ids") Collection<String> ids);
}
