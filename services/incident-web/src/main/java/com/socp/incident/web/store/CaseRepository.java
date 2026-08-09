package com.socp.incident.web.store;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/** 案件仓储：所有查询强制带租户条件（多租户隔离，见 §3.3）。 */
public interface CaseRepository extends JpaRepository<CaseEntity, String> {

    List<CaseEntity> findByTenantId(String tenantId);

    Optional<CaseEntity> findByTenantIdAndId(String tenantId, String id);

    List<CaseEntity> findByTenantIdAndEntityAndStatusIn(String tenantId, String entity, List<String> statuses);
}
