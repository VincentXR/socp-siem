package com.socp.incident.web.persistence.repository;

import com.socp.incident.web.persistence.entity.CaseTimelineEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import com.socp.platform.tenant.persistence.TenantScopedRepository;

import java.util.List;
import java.util.Optional;

public interface CaseTimelineRepository extends TenantScopedRepository<CaseTimelineEntity, String> {
    List<CaseTimelineEntity> findByTenantId(String tenantId);
    Optional<CaseTimelineEntity> findByIdAndTenantId(String id, String tenantId);
    List<CaseTimelineEntity> findByTenantIdAndCaseIdOrderByTsAsc(String tenantId, String caseId);
    Page<CaseTimelineEntity> findByTenantIdAndCaseIdOrderByTsAsc(String tenantId, String caseId, Pageable pageable);
    Optional<CaseTimelineEntity> findByTenantIdAndCaseIdAndEventKey(String tenantId, String caseId, String eventKey);
}
