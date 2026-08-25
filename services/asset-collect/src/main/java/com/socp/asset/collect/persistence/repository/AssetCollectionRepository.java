package com.socp.asset.collect.persistence.repository;



import com.socp.asset.collect.persistence.store.*;
import com.socp.asset.collect.persistence.repository.*;
import com.socp.asset.collect.persistence.entity.*;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AssetCollectionRepository extends JpaRepository<AssetCollectionEntity, String> {

    List<AssetCollectionEntity> findTop200ByTenantIdOrderByCollectedAtDesc(String tenantId);

    long countByTenantId(String tenantId);
}
