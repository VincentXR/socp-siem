package com.socp.incident.web.persistence.repository;



import com.socp.incident.web.persistence.store.*;
import com.socp.incident.web.persistence.repository.*;
import com.socp.incident.web.persistence.entity.*;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** 案件仓储：所有查询强制带租户条件（多租户隔离，见 §3.3）。 */
public interface CaseRepository extends JpaRepository<CaseEntity, String> {

    List<CaseEntity> findByTenantId(String tenantId);

    Optional<CaseEntity> findByTenantIdAndId(String tenantId, String id);

    List<CaseEntity> findByTenantIdAndEntityAndStatusIn(String tenantId, String entity, List<String> statuses);

    @Modifying
    @Transactional
    @Query("update CaseEntity c set c.updatedAt = :updatedAt, c.rowVersion = c.rowVersion + 1 "
            + "where c.tenantId = :tenant and c.id = :id")
    int touchUpdatedAt(@Param("tenant") String tenant, @Param("id") String id,
                       @Param("updatedAt") Instant updatedAt);
}
