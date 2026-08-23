package com.socp.alert;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;

/** 告警仓储：按租户隔离查询（多租户 SDK 级保证，见 §3.3） */
public interface AlarmRepository extends JpaRepository<Alarm, String> {

    List<Alarm> findByTenantId(String tenantId);

    @Query(value = "select a from Alarm a where a.tenantId = :tenant order by a.occurredAt desc nulls last, a.id asc",
           countQuery = "select count(a) from Alarm a where a.tenantId = :tenant")
    Page<Alarm> pageByOccurredAtDesc(String tenant, Pageable pageable);

    @Query(value = "select a from Alarm a where a.tenantId = :tenant order by a.occurredAt asc nulls last, a.id asc",
           countQuery = "select count(a) from Alarm a where a.tenantId = :tenant")
    Page<Alarm> pageByOccurredAtAsc(String tenant, Pageable pageable);

    @Query(value = "select a from Alarm a where a.tenantId = :tenant order by a.alertCreatedAt desc nulls last, a.id asc",
           countQuery = "select count(a) from Alarm a where a.tenantId = :tenant")
    Page<Alarm> pageByAlertCreatedAtDesc(String tenant, Pageable pageable);

    @Query(value = "select a from Alarm a where a.tenantId = :tenant order by a.alertCreatedAt asc nulls last, a.id asc",
           countQuery = "select count(a) from Alarm a where a.tenantId = :tenant")
    Page<Alarm> pageByAlertCreatedAtAsc(String tenant, Pageable pageable);

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
    @Query("select count(a) from Alarm a where a.tenantId = :tenant and a.entity = :entity and a.occurredAt >= :since")
    long countRecentByEntity(String tenant, String entity, java.time.Instant since);

    /** 风险 Top N（风险分倒序），供态势大屏"最该处置的告警" */
    @Query("select a from Alarm a where a.tenantId = :tenant and a.riskScore is not null order by a.riskScore desc")
    List<Alarm> topByRisk(String tenant, org.springframework.data.domain.Pageable pageable);
}
