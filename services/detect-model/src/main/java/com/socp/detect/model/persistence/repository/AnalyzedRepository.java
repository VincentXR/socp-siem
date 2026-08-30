package com.socp.detect.model.persistence.repository;


import com.socp.detect.model.persistence.repository.*;
import com.socp.detect.model.persistence.entity.*;
import com.socp.platform.tenant.persistence.TenantScopedRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public interface AnalyzedRepository extends TenantScopedRepository<AnalyzedEntity, Long> {
    List<AnalyzedEntity> findByTenantId(String tenantId);
    Optional<AnalyzedEntity> findByIdAndTenantId(Long id, String tenantId);
    Page<AnalyzedEntity> findByTenantId(String tenantId, Pageable pageable);

    long countByTenantId(String tenantId);

    @Query("select e.severity, count(e) from AnalyzedEntity e "
            + "where e.tenantId = :tenantId group by e.severity")
    List<Object[]> countBySeverity(@Param("tenantId") String tenantId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("delete from AnalyzedEntity e where e.ts < :cutoff")
    int deleteBefore(@Param("cutoff") Instant cutoff);
}
