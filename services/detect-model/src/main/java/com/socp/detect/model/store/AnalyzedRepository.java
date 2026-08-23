package com.socp.detect.model.store;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.List;

@Repository
public interface AnalyzedRepository extends JpaRepository<AnalyzedEntity, Long> {
    Page<AnalyzedEntity> findByTenantId(String tenantId, Pageable pageable);

    long countByTenantId(String tenantId);

    @Query("select e.severity, count(e) from AnalyzedEntity e "
            + "where e.tenantId = :tenantId group by e.severity")
    List<Object[]> countBySeverity(@Param("tenantId") String tenantId);

    @Modifying
    @Query("delete from AnalyzedEntity e where e.ts < :cutoff")
    int deleteBefore(@Param("cutoff") Instant cutoff);
}
