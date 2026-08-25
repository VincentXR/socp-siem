package com.socp.search.config.persistence.repository;



import com.socp.search.config.persistence.store.*;
import com.socp.search.config.persistence.repository.*;
import com.socp.search.config.persistence.entity.*;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TenantCatalogEntryRepository extends JpaRepository<TenantCatalogEntry, Long> {

    Optional<TenantCatalogEntry> findByCatalogTypeAndTenantIdAndItemId(
            String catalogType, String tenantId, String itemId);

    List<TenantCatalogEntry> findByCatalogTypeAndTenantId(String catalogType, String tenantId);
}
