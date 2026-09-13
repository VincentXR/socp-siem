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

    Optional<IocEntity> findByTenantIdAndValue(String tenantId, String value);

    List<IocEntity> findByTenantIdAndValueIn(String tenantId, java.util.Collection<String> values);

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
