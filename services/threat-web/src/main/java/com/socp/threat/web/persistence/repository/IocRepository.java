package com.socp.threat.web.persistence.repository;


import com.socp.threat.web.persistence.entity.IocEntity;
import com.socp.platform.tenant.persistence.TenantScopedRepository;

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

    long countByTenantId(String tenantId);

    /** Deletes only non-revoked indicators whose feed validity has expired. */
    long deleteByExpirationBeforeAndRevokedFalse(Instant at);

    /** Deletes only non-revoked indicators whose validity window has ended. */
    long deleteByValidUntilBeforeAndRevokedFalse(Instant at);
}
