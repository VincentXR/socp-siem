package com.socp.asset.web.persistence.repository;



import com.socp.asset.web.persistence.store.*;
import com.socp.asset.web.persistence.repository.*;
import com.socp.asset.web.persistence.entity.*;
import com.socp.platform.tenant.persistence.TenantScopedRepository;

import java.util.List;
import java.util.Optional;

/** 资产仓储（H2/PG）。 */
public interface AssetRepository extends TenantScopedRepository<AssetEntity, String> {

    List<AssetEntity> findByTenantId(String tenantId);

    Optional<AssetEntity> findByIdAndTenantId(String id, String tenantId);

    List<AssetEntity> findByIpAndTenantId(String ip, String tenantId);

    long countByTenantId(String tenantId);
}
