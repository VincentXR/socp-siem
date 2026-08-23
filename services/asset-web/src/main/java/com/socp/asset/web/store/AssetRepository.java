package com.socp.asset.web.store;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/** 资产仓储（H2/PG）。 */
public interface AssetRepository extends JpaRepository<AssetEntity, String> {

    List<AssetEntity> findByTenantId(String tenantId);

    Optional<AssetEntity> findByIdAndTenantId(String id, String tenantId);

    List<AssetEntity> findByIpAndTenantId(String ip, String tenantId);

    long countByTenantId(String tenantId);
}
