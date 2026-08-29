package com.socp.threat.web.persistence.repository;



import com.socp.threat.web.persistence.store.*;
import com.socp.threat.web.persistence.repository.*;
import com.socp.threat.web.persistence.entity.*;
import com.socp.platform.tenant.persistence.TenantScopedRepository;

import java.util.List;
import java.util.Optional;

/** 威胁情报 IOC 仓储。 */
public interface IocRepository extends TenantScopedRepository<IocEntity, String> {

    Optional<IocEntity> findByTenantIdAndValue(String tenantId, String value);

    List<IocEntity> findByTenantIdAndValueIn(String tenantId, java.util.Collection<String> values);

    Optional<IocEntity> findByIdAndTenantId(String id, String tenantId);

    List<IocEntity> findByTenantId(String tenantId);

    long countByTenantId(String tenantId);
}
