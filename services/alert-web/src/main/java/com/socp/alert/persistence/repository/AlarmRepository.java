package com.socp.alert.persistence.repository;

import com.socp.alert.domain.Alarm;
import com.socp.alert.domain.AlarmRiskLevelCount;
import com.socp.alert.domain.AlarmRuleCount;
import com.socp.alert.domain.AlarmSeverityCount;
import com.socp.alert.domain.Severity;


import org.springframework.data.domain.Pageable;
import com.socp.platform.tenant.persistence.TenantScopedRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Tenant-scoped persistence plus database aggregation projections for alarms. */
public interface AlarmRepository extends TenantScopedRepository<Alarm, String>, AlarmRepositoryCustom {

    List<Alarm> findByTenantId(String tenantId);

    Optional<Alarm> findByTenantIdAndId(String tenantId, String id);

    Optional<Alarm> findByTenantIdAndSourceAlertId(String tenantId, String sourceAlertId);

    /**
     * Applies asynchronous TI enrichment without merging a detached Alarm and
     * overwriting fields changed concurrently by an analyst or another flow.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("""
           update Alarm a
              set a.tiHits = :tiHits,
                  a.riskScore = :riskScore,
                  a.riskLevel = :riskLevel
            where a.tenantId = :tenant
              and a.id = :id
           """)
    int updateEnrichment(@Param("tenant") String tenant,
                         @Param("id") String id,
                         @Param("tiHits") String tiHits,
                         @Param("riskScore") Integer riskScore,
                         @Param("riskLevel") String riskLevel);

    /** Same tenant/rule/entity candidates used by alert investigation drill-down. */
    @Query("""
           select a from Alarm a
           where a.tenantId = :tenant
             and a.id <> :alarmId
             and a.ruleId = :ruleId
             and ((:entity is null and a.entity is null) or a.entity = :entity)
           order by a.occurredAt desc, a.id asc
           """)
    List<Alarm> findSimilar(@Param("tenant") String tenant,
                            @Param("alarmId") String alarmId,
                            @Param("ruleId") String ruleId,
                            @Param("entity") String entity,
                            Pageable pageable);

    List<Alarm> findByTenantIdAndSeverity(String tenantId, Severity severity);

    @Query("""
           select a from Alarm a
           where a.tenantId = :tenant
             and (a.entity like %:q% or a.ruleName like %:q% or a.message like %:q%)
           """)
    List<Alarm> search(String tenant, String q);

    /** Recent per-entity count used by risk enrichment. */
    @Query("select count(a) from Alarm a where a.tenantId = :tenant and a.entity = :entity and a.occurredAt >= :since")
    long countRecentByEntity(String tenant, String entity, Instant since);

    @Query("""
           select count(a) from Alarm a
           where a.tenantId = :tenant
             and a.occurredAt >= :since
           """)
    long countForStatistics(@Param("tenant") String tenant, @Param("since") Instant since);

    @Query("""
           select new com.socp.alert.domain.AlarmSeverityCount(a.severity, count(a))
           from Alarm a
           where a.tenantId = :tenant
             and a.occurredAt >= :since
           group by a.severity
           """)
    List<AlarmSeverityCount> countBySeverityForStatistics(
            @Param("tenant") String tenant, @Param("since") Instant since);

    @Query("""
           select new com.socp.alert.domain.AlarmRuleCount(a.ruleId, count(a))
           from Alarm a
           where a.tenantId = :tenant
             and a.occurredAt >= :since
           group by a.ruleId
           order by count(a) desc, a.ruleId asc
           """)
    List<AlarmRuleCount> topRulesForStatistics(
            @Param("tenant") String tenant, @Param("since") Instant since, Pageable pageable);

    @Query("""
           select new com.socp.alert.domain.AlarmRiskLevelCount(
             case
               when a.riskScore >= 85 then 'CRITICAL'
               when a.riskScore >= 65 then 'HIGH'
               when a.riskScore >= 40 then 'MEDIUM'
               when a.riskScore >= 20 then 'LOW'
               else 'INFO'
             end,
             count(a))
           from Alarm a
           where a.tenantId = :tenant
             and a.riskScore is not null
             and a.occurredAt >= :since
           group by case
               when a.riskScore >= 85 then 'CRITICAL'
               when a.riskScore >= 65 then 'HIGH'
               when a.riskScore >= 40 then 'MEDIUM'
               when a.riskScore >= 20 then 'LOW'
               else 'INFO'
             end
           """)
    List<AlarmRiskLevelCount> countByRiskLevelForStatistics(
            @Param("tenant") String tenant, @Param("since") Instant since);

    @Query("""
           select avg(a.riskScore) from Alarm a
           where a.tenantId = :tenant
             and a.riskScore is not null
             and a.occurredAt >= :since
           """)
    Double averageRiskForStatistics(@Param("tenant") String tenant, @Param("since") Instant since);

    @Query("""
           select a from Alarm a
           where a.tenantId = :tenant
             and a.riskScore is not null
             and a.occurredAt >= :since
           order by a.riskScore desc, a.id asc
           """)
    List<Alarm> topRiskForStatistics(
            @Param("tenant") String tenant, @Param("since") Instant since, Pageable pageable);

    long countByTenantIdAndOccurredAtGreaterThanEqualAndOccurredAtLessThan(
            String tenantId, Instant startInclusive, Instant endExclusive);
}
