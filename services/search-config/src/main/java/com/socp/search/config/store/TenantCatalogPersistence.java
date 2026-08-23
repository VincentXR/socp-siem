package com.socp.search.config.store;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Database authority for tenant-owned catalog overlays and template
 * tombstones. Reads deliberately do not cache: configuration mutations made
 * through another Search instance are immediately visible to this instance.
 */
@Component
public class TenantCatalogPersistence {

    private final TenantCatalogEntryRepository repository;

    TenantCatalogPersistence(TenantCatalogEntryRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    StoredEntry find(String catalogType, String tenantId, String itemId) {
        return repository.findByCatalogTypeAndTenantIdAndItemId(catalogType, tenantId, itemId)
                .map(entry -> new StoredEntry(entry.getItemId(), entry.getPayload(), entry.isDeleted()))
                .orElse(null);
    }

    @Transactional(readOnly = true)
    List<StoredEntry> list(String catalogType, String tenantId) {
        return repository.findByCatalogTypeAndTenantId(catalogType, tenantId).stream()
                .map(entry -> new StoredEntry(entry.getItemId(), entry.getPayload(), entry.isDeleted()))
                .toList();
    }

    @Transactional
    void save(String catalogType, String tenantId, String itemId, String payload) {
        TenantCatalogEntry entry = repository.findByCatalogTypeAndTenantIdAndItemId(catalogType, tenantId, itemId)
                .orElseGet(() -> new TenantCatalogEntry(catalogType, tenantId, itemId));
        entry.savePayload(payload);
        repository.save(entry);
    }

    @Transactional
    void delete(String catalogType, String tenantId, String itemId) {
        TenantCatalogEntry entry = repository.findByCatalogTypeAndTenantIdAndItemId(catalogType, tenantId, itemId)
                .orElseGet(() -> new TenantCatalogEntry(catalogType, tenantId, itemId));
        entry.markDeleted();
        repository.save(entry);
    }

    record StoredEntry(String itemId, String payload, boolean deleted) {
    }
}
