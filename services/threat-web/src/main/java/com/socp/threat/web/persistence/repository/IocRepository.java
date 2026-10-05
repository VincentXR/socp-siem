package com.socp.threat.web.persistence.repository;


import com.socp.threat.web.persistence.entity.IocEntity;
import com.socp.platform.tenant.persistence.TenantScopedRepository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.time.Instant;

/** 威胁情报 IOC 仓储。 */
public interface IocRepository extends TenantScopedRepository<IocEntity, String> {

    Optional<IocEntity> findByTenantIdAndIdentityKey(String tenantId, String identityKey);

    /** One authoritative, active source per value, ordered by risk then stable identity.
     * The window bounds results by the input size even when many feeds match a value. */
    @Query(value = """
            select i.* from t_ioc i join (
                select id, row_number() over (partition by ioc_value order by
                    case severity when 'CRITICAL' then 5 when 'HIGH' then 4
                        when 'MEDIUM' then 3 when 'LOW' then 2 else 1 end desc, id) as match_rank
                from t_ioc where tenant_id = :tenantId and ioc_value in (:values)
                    and revoked = false
                    and (valid_from is null or valid_from <= :at)
                    and (valid_until is null or valid_until > :at)
                    and (expiration is null or expiration > :at)
            ) ranked on ranked.id = i.id
            where ranked.match_rank = 1 and i.tenant_id = :tenantId
            """, nativeQuery = true)
    List<IocEntity> findActiveMatches(@Param("tenantId") String tenantId,
                                    @Param("values") java.util.Collection<String> values,
                                    @Param("at") Instant at);

    Optional<IocEntity> findByIdAndTenantId(String id, String tenantId);

    Optional<IocEntity> findByTenantIdAndSourceAndExternalId(String tenantId, String source,
                                                               String externalId);

    List<IocEntity> findByTenantId(String tenantId);

    @Query("""
            select i from IocEntity i
             where i.tenantId = :tenantId
               and (:type = '' or lower(i.type) = lower(:type))
               and (:query = ''
                    or lower(coalesce(i.value, '')) like lower(concat('%', :query, '%'))
                    or lower(coalesce(i.severity, '')) like lower(concat('%', :query, '%'))
                    or lower(coalesce(i.source, '')) like lower(concat('%', :query, '%'))
                    or lower(coalesce(i.description, '')) like lower(concat('%', :query, '%'))
                    or lower(coalesce(i.tagsJson, '')) like lower(concat('%', :query, '%')))
            """)
    Page<IocEntity> searchPage(@Param("tenantId") String tenantId,
                               @Param("type") String type,
                               @Param("query") String query,
                               Pageable pageable);

    long countByTenantId(String tenantId);

    /** Deletes only non-revoked indicators whose feed validity has expired. */
    long deleteByExpirationBeforeAndRevokedFalse(Instant at);

    /** Deletes only non-revoked indicators whose validity window has ended. */
    long deleteByValidUntilBeforeAndRevokedFalse(Instant at);
}
