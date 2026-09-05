package com.socp.soar.web.persistence.repository;

import com.socp.platform.tenant.persistence.TenantScopedRepository;
import com.socp.soar.web.persistence.entity.SoarRunEventEntity;

import java.util.List;
import java.util.Optional;
import java.util.Collection;
import java.time.Instant;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SoarRunEventRepository extends TenantScopedRepository<SoarRunEventEntity, String> {
    List<SoarRunEventEntity> findByTenantIdAndRunIdOrderBySequenceNoAsc(String tenantId, String runId);
    Optional<SoarRunEventEntity> findTopByTenantIdAndRunIdOrderBySequenceNoDesc(String tenantId, String runId);
    Page<SoarRunEventEntity> findByTenantIdAndRunIdAndSequenceNoGreaterThanOrderBySequenceNoAsc(
            String tenantId, String runId, long sequence, Pageable pageable);

    /** System-scope retention scan; call only from SoarRunRetentionWorker. */
    @Query("select e.id from SoarRunEventEntity e where e.createdAt < :cutoff order by e.createdAt asc")
    List<String> findIdsCreatedBefore(@Param("cutoff") Instant cutoff, Pageable pageable);

    /** System-scope retention purge; call only from SoarRunRetentionWorker. */
    @Modifying
    @Query("delete from SoarRunEventEntity e where e.id in :ids")
    int deleteByIds(@Param("ids") Collection<String> ids);
}
