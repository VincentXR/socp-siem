package com.socp.asset.collect.persistence.repository;


import com.socp.asset.collect.persistence.entity.AssetCollectionEntity;
import com.socp.platform.tenant.persistence.TenantScopedRepository;

import java.util.List;
import java.util.Optional;

public interface AssetCollectionRepository extends TenantScopedRepository<AssetCollectionEntity, String> {
    List<AssetCollectionEntity> findByTenantId(String tenantId);
    Optional<AssetCollectionEntity> findByIdAndTenantId(String id, String tenantId);

    List<AssetCollectionEntity> findTop200ByTenantIdOrderByCollectedAtDesc(String tenantId);

    long countByTenantId(String tenantId);
}
