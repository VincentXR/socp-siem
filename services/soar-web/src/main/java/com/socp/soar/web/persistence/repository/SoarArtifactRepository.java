package com.socp.soar.web.persistence.repository;

import com.socp.platform.tenant.persistence.TenantScopedRepository;
import com.socp.soar.web.persistence.entity.SoarArtifactEntity;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface SoarArtifactRepository extends TenantScopedRepository<SoarArtifactEntity, String> {
    Optional<SoarArtifactEntity> findByTenantIdAndId(String tenantId, String id);
    List<SoarArtifactEntity> findByTenantIdAndRunIdOrderByCreatedAtAsc(String tenantId, String runId);
    Page<SoarArtifactEntity> findByTenantIdAndRunIdOrderByCreatedAtAsc(String tenantId, String runId,
                                                                         Pageable pageable);
    List<SoarArtifactEntity> findTop100ByExpiresAtBeforeOrderByExpiresAtAsc(Instant now);

    /** Delete only the rows whose remote object has been handled by retention. */
    @Modifying
    @Transactional
    @Query("delete from SoarArtifactEntity a where a.id in :ids "
            + "and a.expiresAt is not null and a.expiresAt < :now")
    int deleteByIds(@Param("ids") Collection<String> ids, @Param("now") Instant now);

    /** Return runs that still have artifact handles awaiting their own retention pass. */
    @Query("select distinct a.runId from SoarArtifactEntity a where a.runId in :runIds")
    List<String> findRunIdsByRunIdIn(@Param("runIds") Collection<String> runIds);
}
