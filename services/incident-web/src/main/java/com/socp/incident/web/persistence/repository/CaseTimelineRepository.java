package com.socp.incident.web.persistence.repository;

import com.socp.incident.web.persistence.entity.CaseTimelineEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CaseTimelineRepository extends JpaRepository<CaseTimelineEntity, String> {
    List<CaseTimelineEntity> findByTenantIdAndCaseIdOrderByTsAsc(String tenantId, String caseId);
    Page<CaseTimelineEntity> findByTenantIdAndCaseIdOrderByTsAsc(String tenantId, String caseId, Pageable pageable);
    Optional<CaseTimelineEntity> findByTenantIdAndCaseIdAndEventKey(String tenantId, String caseId, String eventKey);
}
