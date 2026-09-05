package com.socp.soar.web.persistence.repository;

import com.socp.platform.tenant.persistence.TenantScopedRepository;
import com.socp.soar.web.persistence.entity.SoarRunEventEntity;

import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface SoarRunEventRepository extends TenantScopedRepository<SoarRunEventEntity, String> {
    List<SoarRunEventEntity> findByTenantIdAndRunIdOrderBySequenceNoAsc(String tenantId, String runId);
    Optional<SoarRunEventEntity> findTopByTenantIdAndRunIdOrderBySequenceNoDesc(String tenantId, String runId);
    Page<SoarRunEventEntity> findByTenantIdAndRunIdAndSequenceNoGreaterThanOrderBySequenceNoAsc(
            String tenantId, String runId, long sequence, Pageable pageable);
}
