package com.socp.soar.web.persistence.repository;

import com.socp.platform.tenant.persistence.TenantScopedRepository;
import com.socp.soar.web.persistence.entity.SoarRunEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
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

    /**
     * Retention candidates that no longer have timeline or artifact evidence.
     * The evidence tables intentionally outlive the short-lived run family;
     * filtering them in SQL prevents an old retained run from starving newer
     * purgeable rows at the head of the top-100 scan.
     */
    @Query("select r from SoarRunEntity r "
            + "where r.status in :statuses "
            + "and r.updatedAt < :updatedBefore "
            + "and not exists (select e.id from SoarRunEventEntity e where e.runId = r.id) "
            + "and not exists (select a.id from SoarArtifactEntity a where a.runId = r.id) "
            + "order by r.updatedAt asc")
    List<SoarRunEntity> findTopPurgeableByStatusInAndUpdatedAtBefore(
            @Param("statuses") Collection<String> statuses,
            @Param("updatedBefore") Instant updatedBefore,
            Pageable pageable);
    long countByTenantIdAndPlaybookVersionIdAndStatusIn(String tenantId, String playbookVersionId,
                                                        Collection<String> statuses);
    long countByTenantIdAndPlaybookVersionIdInAndStatusIn(String tenantId, Collection<String> playbookVersionIds,
                                                          Collection<String> statuses);
    long countByTenantIdAndStatus(String tenantId, String status);
    /** System-scope aggregate used only for low-cardinality metrics/health. */
    long countByStatus(String status);

    /** System-scope retention purge; call only from SoarRunRetentionWorker. */
    @Modifying
    @org.springframework.transaction.annotation.Transactional
    @Query("delete from SoarRunEntity r where r.id in :ids")
    int deleteByIds(@Param("ids") Collection<String> ids);
}
