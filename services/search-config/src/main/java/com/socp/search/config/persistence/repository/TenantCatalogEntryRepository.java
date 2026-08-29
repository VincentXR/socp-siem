package com.socp.search.config.persistence.repository;



import com.socp.search.config.persistence.store.*;
import com.socp.search.config.persistence.repository.*;
import com.socp.search.config.persistence.entity.*;
import com.socp.platform.tenant.persistence.TenantScopedRepository;

import java.util.List;
import java.util.Optional;

public interface TenantCatalogEntryRepository extends TenantScopedRepository<TenantCatalogEntry, Long> {
    java.util.List<TenantCatalogEntry> findByTenantId(String tenantId);
    java.util.Optional<TenantCatalogEntry> findByIdAndTenantId(Long id, String tenantId);

    Optional<TenantCatalogEntry> findByCatalogTypeAndTenantIdAndItemId(
            String catalogType, String tenantId, String itemId);

    List<TenantCatalogEntry> findByCatalogTypeAndTenantId(String catalogType, String tenantId);
}
