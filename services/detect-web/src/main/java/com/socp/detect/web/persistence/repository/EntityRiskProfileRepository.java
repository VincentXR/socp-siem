package com.socp.detect.web.persistence.repository;

import com.socp.detect.web.persistence.entity.EntityRiskProfileEntity;

import jakarta.persistence.LockModeType;
import com.socp.platform.tenant.persistence.TenantScopedRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.List;

public interface EntityRiskProfileRepository extends TenantScopedRepository<EntityRiskProfileEntity, String> {
    // Match the public one-decimal risk value before ranking or classifying.
    // A valid stored score is within 0..100; after 16 half-lives every such
    // value rounds to zero. Guard that branch to avoid floating underflow.
    String AGE = "greatest(0, :epoch - floor(extract(epoch from score_at)))";
    String RISK = "case when score < 0.05 or (score <= 100 and " + AGE + ">=345600) then 0 "
            + "else round(cast(score * power(0.5, " + AGE + "/21600.0) as numeric(40,20)),1) end";

    @Query(value = "select p.* from t_entity_risk_profile p where tenant_id=:tenantId order by "
            + RISK + " desc, entity_key asc limit :limit", nativeQuery = true)
    List<EntityRiskProfileEntity> topAt(@Param("tenantId") String tenantId,
                                      @Param("epoch") long epoch, @Param("limit") int limit);

    interface RiskSummary {
        long getEntities();
        double getMaximum();
        long getCritical();
        long getHigh();
        long getMedium();
        long getLow();
        long getInfo();
    }

    @Query(value = "select count(*) as entities, coalesce(max(risk),0) as maximum, "
            + "coalesce(sum(case when risk>=84.5 then 1 else 0 end),0) as critical, "
            + "coalesce(sum(case when risk>=64.5 and risk<84.5 then 1 else 0 end),0) as high, "
            + "coalesce(sum(case when risk>=39.5 and risk<64.5 then 1 else 0 end),0) as medium, "
            + "coalesce(sum(case when risk>=19.5 and risk<39.5 then 1 else 0 end),0) as low, "
            + "coalesce(sum(case when risk<19.5 then 1 else 0 end),0) as info "
            + "from (select " + RISK + " as risk from t_entity_risk_profile where tenant_id=:tenantId) r",
            nativeQuery = true)
    RiskSummary summarizeAt(@Param("tenantId") String tenantId, @Param("epoch") long epoch);

    Optional<EntityRiskProfileEntity> findByStorageIdAndTenantId(String storageId, String tenantId);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from EntityRiskProfileEntity p where p.tenantId = :tenantId and p.entity = :entity")
    Optional<EntityRiskProfileEntity> findForUpdate(@Param("tenantId") String tenantId,
                                                    @Param("entity") String entity);

    Optional<EntityRiskProfileEntity> findByTenantIdAndEntity(String tenantId, String entity);

}
