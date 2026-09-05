package com.socp.soar.web.persistence.repository;

import com.socp.platform.tenant.persistence.TenantScopedRepository;
import com.socp.soar.web.persistence.entity.SoarArtifactEntity;

import java.time.Instant;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import java.util.List;
import java.util.Optional;
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

    /** System-scope retention operation used only by the scheduled cleanup job. */
    @Modifying
    @Transactional
    @Query("delete from SoarArtifactEntity a where a.expiresAt is not null and a.expiresAt < :now")
    int deleteExpired(@Param("now") Instant now);
}
