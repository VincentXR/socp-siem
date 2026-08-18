package com.socp.alert;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

/** 告警仓储：按租户隔离查询（多租户 SDK 级保证，见 §3.3） */
public interface AlarmRepository extends JpaRepository<Alarm, String> {

    List<Alarm> findByTenantId(String tenantId);

    Optional<Alarm> findByTenantIdAndId(String tenantId, String id);

    Optional<Alarm> findByTenantIdAndSourceAlertId(String tenantId, String sourceAlertId);

    List<Alarm> findByTenantIdAndSeverity(String tenantId, Severity severity);

    @Query("""
           select a from Alarm a
           where a.tenantId = :tenant
             and (a.entity like %:q% or a.ruleName like %:q% or a.message like %:q%)
           """)
    List<Alarm> search(String tenant, String q);

    @Query("""
           select a from Alarm a
           where a.tenantId = :tenant
             and (:severity is null or a.severity = :severity)
             and (:rule is null or a.ruleId = :rule)
             and (:status is null or a.status = :status)
             and (:q is null or (a.entity like %:q% or a.ruleName like %:q% or a.message like %:q%))
           order by a.occurredAt desc
           """)
    List<Alarm> query(String tenant, Severity severity, String rule, String status, String q);

    /** 同实体近期告警数，供威胁评分的"行为频次"分项使用 */
    @Query("select count(a) from Alarm a where a.entity = :entity and a.occurredAt >= :since")
    long countRecentByEntity(String entity, java.time.Instant since);

    /** 风险 Top N（风险分倒序），供态势大屏"最该处置的告警" */
    @Query("select a from Alarm a where a.riskScore is not null order by a.riskScore desc")
    List<Alarm> topByRisk(org.springframework.data.domain.Pageable pageable);
}
