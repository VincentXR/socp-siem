package com.socp.asset.collect.store;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AssetCollectionRepository extends JpaRepository<AssetCollectionEntity, String> {

    List<AssetCollectionEntity> findTop200ByTenantIdOrderByCollectedAtDesc(String tenantId);

    long countByTenantId(String tenantId);
}
