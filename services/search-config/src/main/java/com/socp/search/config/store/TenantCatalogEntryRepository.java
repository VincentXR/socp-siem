package com.socp.search.config.store;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

interface TenantCatalogEntryRepository extends JpaRepository<TenantCatalogEntry, Long> {

    Optional<TenantCatalogEntry> findByCatalogTypeAndTenantIdAndItemId(
            String catalogType, String tenantId, String itemId);

    List<TenantCatalogEntry> findByCatalogTypeAndTenantId(String catalogType, String tenantId);
}
