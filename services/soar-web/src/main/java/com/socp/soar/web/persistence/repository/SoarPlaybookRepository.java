package com.socp.soar.web.persistence.repository;

import com.socp.platform.tenant.persistence.TenantScopedRepository;
import com.socp.soar.web.persistence.entity.SoarPlaybookEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

import java.util.Optional;

public interface SoarPlaybookRepository extends TenantScopedRepository<SoarPlaybookEntity, String> {
    Optional<SoarPlaybookEntity> findByTenantIdAndId(String tenantId, String id);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from SoarPlaybookEntity p where p.tenantId = :tenantId and p.id = :id")
    Optional<SoarPlaybookEntity> findByTenantIdAndIdForUpdate(@Param("tenantId") String tenantId,
                                                               @Param("id") String id);
    @Query("select p from SoarPlaybookEntity p where p.tenantId = :tenantId order by p.updatedAt desc, p.id asc")
    Page<SoarPlaybookEntity> findByTenantId(String tenantId, Pageable pageable);

    /**
     * Server-side metadata filters keep the common operator queries paged in
     * the database.  Tag/risk filtering is deliberately string based here so
     * the same query works on PostgreSQL and the H2 migration test database;
     * the application service applies the final token/risk check before it
     * constructs the response envelope.
     */
    @Query("select p from SoarPlaybookEntity p "
            + "where p.tenantId = :tenantId "
            // Keep functions on the mapped column, not on nullable bind
            // parameters. PostgreSQL otherwise infers a null parameter as
            // bytea and rejects upper(:status)/lower(:owner).
            + "and (:status is null or upper(p.status) = :status) "
            + "and (:owner is null or lower(coalesce(p.owner, '')) = :owner) "
            + "and (cast(:tag as string) is null or lower(coalesce(p.tagsJson, '')) "
            + "like concat('%', cast(:tag as string), '%')) "
            + "order by p.updatedAt desc, p.id asc")
    Page<SoarPlaybookEntity> searchByTenant(@Param("tenantId") String tenantId,
                                            @Param("status") String status,
                                            @Param("owner") String owner,
                                            @Param("tag") String tag,
                                            Pageable pageable);
    /** Exact token and numeric metadata predicates; no JSON hydration before database paging. */
    @Query("select p from SoarPlaybookEntity p where p.tenantId = :tenantId "
            + "and (:status is null or upper(p.status) = :status) "
            + "and (:owner is null or lower(coalesce(p.owner, '')) = :owner) "
            + "and (:tag is null or locate(:tag, coalesce(p.tagTokens, '')) > 0) "
            + "and (:risk is null or (:risk = 'NONE' and not exists (select v.id from PlaybookVersionEntity v "
            + "where v.tenantId = :tenantId and v.playbookId = p.id and v.status = 'PUBLISHED')) "
            + "or exists (select v.id from PlaybookVersionEntity v where v.tenantId = :tenantId "
            + "and v.playbookId = p.id and v.status = 'PUBLISHED' and v.versionNo = "
            + "(select max(v2.versionNo) from PlaybookVersionEntity v2 where v2.tenantId = :tenantId "
            + "and v2.playbookId = p.id and v2.status = 'PUBLISHED') "
            + "and ((:risk in ('HIGH','CRITICAL') and v.highRiskActionCount > 0) "
            + "or (:risk in ('LOW','READ_ONLY') and v.highRiskActionCount = 0) "
            + "or (:risk = 'MEDIUM' and v.highRiskActionCount = 0 and v.actionCount > 0) "
            + "or (:risk = 'NONE' and v.actionCount = 0)))) order by p.updatedAt desc, p.id asc")
    Page<SoarPlaybookEntity> searchCatalog(@Param("tenantId") String tenantId,
            @Param("status") String status, @Param("owner") String owner,
            @Param("tag") String tag, @Param("risk") String risk, Pageable pageable);
}
