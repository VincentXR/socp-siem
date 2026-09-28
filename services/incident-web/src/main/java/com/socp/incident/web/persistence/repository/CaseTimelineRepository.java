package com.socp.incident.web.persistence.repository;

import com.socp.incident.web.persistence.entity.CaseTimelineEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import com.socp.platform.tenant.persistence.TenantScopedRepository;

import java.util.List;
import java.util.Optional;

public interface CaseTimelineRepository extends TenantScopedRepository<CaseTimelineEntity, String> {
    List<CaseTimelineEntity> findByTenantId(String tenantId);
    Optional<CaseTimelineEntity> findByIdAndTenantId(String id, String tenantId);
    List<CaseTimelineEntity> findTop500ByTenantIdAndCaseIdOrderByTsAscIdAsc(String tenantId, String caseId);
    Page<CaseTimelineEntity> findByTenantIdAndCaseIdOrderByTsAsc(String tenantId, String caseId, Pageable pageable);
    Optional<CaseTimelineEntity> findByTenantIdAndCaseIdAndEventKey(String tenantId, String caseId, String eventKey);

    @Modifying
    @Query(value = """
            INSERT INTO t_case_timeline
              (id, tenant_id, case_id, event_key, ts, type, message, source, alarm_id, created_at)
            VALUES
              (:id, :tenantId, :caseId, :eventKey, :ts, :type, :message, :source, :alarmId, :createdAt)
            ON CONFLICT (tenant_id, case_id, event_key) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(@Param("id") String id,
                       @Param("tenantId") String tenantId,
                       @Param("caseId") String caseId,
                       @Param("eventKey") String eventKey,
                       @Param("ts") java.time.Instant ts,
                       @Param("type") String type,
                       @Param("message") String message,
                       @Param("source") String source,
                       @Param("alarmId") String alarmId,
                       @Param("createdAt") java.time.Instant createdAt);
}
