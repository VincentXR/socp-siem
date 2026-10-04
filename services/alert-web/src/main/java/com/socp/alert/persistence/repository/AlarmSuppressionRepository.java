package com.socp.alert.persistence.repository;

import com.socp.alert.persistence.entity.AlarmSuppressionEntity;
import com.socp.platform.tenant.persistence.TenantScopedRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface AlarmSuppressionRepository extends TenantScopedRepository<AlarmSuppressionEntity, String> {

    /**
     * Active windows for one detection scope. The exact entity and the rule-wide
     * empty key are resolved in one index range scan because every incoming alarm
     * asks this question on the creation path.
     */
    @Query("select s from AlarmSuppressionEntity s where s.tenantId = :tenant"
            + " and s.ruleId = :ruleId and s.entityKey in :entityKeys and s.expiresAt > :now")
    List<AlarmSuppressionEntity> findActive(@Param("tenant") String tenant,
                                            @Param("ruleId") String ruleId,
                                            @Param("entityKeys") Collection<String> entityKeys,
                                            @Param("now") Instant now);

    Optional<AlarmSuppressionEntity> findByTenantIdAndRuleIdAndEntityKey(String tenantId, String ruleId, String entityKey);

    List<AlarmSuppressionEntity> findByTenantIdOrderByExpiresAtDesc(String tenantId);

    long countByTenantId(String tenantId);

    void deleteByTenantIdAndRuleIdAndEntityKey(String tenantId, String ruleId, String entityKey);

    void deleteByTenantIdAndExpiresAtLessThanEqual(String tenantId, Instant cutoff);
}
