package com.socp.incident.web.persistence.repository;


import com.socp.incident.web.persistence.entity.AlarmCaseLinkEntity;
import com.socp.platform.tenant.persistence.TenantScopedRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.List;

public interface AlarmCaseLinkRepository extends TenantScopedRepository<AlarmCaseLinkEntity, String> {
    List<AlarmCaseLinkEntity> findByTenantId(String tenantId);
    Optional<AlarmCaseLinkEntity> findByIdAndTenantId(String id, String tenantId);
    Optional<AlarmCaseLinkEntity> findByTenantIdAndAlarmId(String tenantId, String alarmId);
    List<AlarmCaseLinkEntity> findByTenantIdAndCaseIdOrderByAlarmIdAsc(String tenantId, String caseId);
    List<AlarmCaseLinkEntity> findByTenantIdAndCaseIdIn(String tenantId, List<String> caseIds);
    Page<AlarmCaseLinkEntity> findByTenantIdAndCaseIdOrderByAlarmIdAsc(
            String tenantId, String caseId, Pageable pageable);
    long countByTenantIdAndCaseId(String tenantId, String caseId);

    @Query("select a.caseId, count(a) from AlarmCaseLinkEntity a "
            + "where a.tenantId = :tenantId and a.caseId in :caseIds group by a.caseId")
    List<Object[]> countByCaseIds(@Param("tenantId") String tenantId,
                                  @Param("caseIds") List<String> caseIds);

    @Modifying
    @Query(value = """
            INSERT INTO t_alarm_case_link (id, tenant_id, alarm_id, case_id, created_at)
            VALUES (:id, :tenantId, :alarmId, :caseId, :createdAt)
            ON CONFLICT (tenant_id, alarm_id) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(@Param("id") String id,
                       @Param("tenantId") String tenantId,
                       @Param("alarmId") String alarmId,
                       @Param("caseId") String caseId,
                       @Param("createdAt") java.time.Instant createdAt);
}
