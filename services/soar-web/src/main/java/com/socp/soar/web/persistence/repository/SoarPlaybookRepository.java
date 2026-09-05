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
            + "and (:status is null or upper(p.status) = upper(:status)) "
            + "and (:owner is null or lower(coalesce(p.owner, '')) = lower(:owner)) "
            + "and (:tag is null or lower(coalesce(p.tagsJson, '')) like lower(concat('%', :tag, '%'))) "
            + "order by p.updatedAt desc")
    Page<SoarPlaybookEntity> searchByTenant(@Param("tenantId") String tenantId,
                                            @Param("status") String status,
                                            @Param("owner") String owner,
                                            @Param("tag") String tag,
                                            Pageable pageable);
}
