package com.socp.search.config.persistence.store;


import com.socp.search.config.persistence.repository.TenantCatalogEntryRepository;
import com.socp.search.config.config.SearchRuntimeRole;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Database authority for tenant-owned catalog overlays and template
 * tombstones. Reads deliberately do not cache: configuration mutations made
 * through another Search instance are immediately visible to this instance.
 */
@Component
@SearchRuntimeRole(SearchRuntimeRole.Role.API)
public class TenantCatalogPersistence {

    private final TenantCatalogEntryRepository repository;
    private final org.springframework.transaction.support.TransactionTemplate mutationTransaction;

    TenantCatalogPersistence(TenantCatalogEntryRepository repository,
                             org.springframework.transaction.PlatformTransactionManager transactionManager) {
        this.repository = repository;
        mutationTransaction = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        mutationTransaction.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        mutationTransaction.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_SERIALIZABLE);
        mutationTransaction.setTimeout(5);
    }

    /** Retry the entire read/validate/write operation, never a stale serialized payload. */
    <T> T mutate(java.util.function.Supplier<T> operation) {
        for (int attempt = 0; attempt < 5; attempt++) {
            try {
                return mutationTransaction.execute(status -> operation.get());
            } catch (RuntimeException failure) {
                if (!retryable(failure)) throw failure;
                if (attempt < 4) pauseBeforeRetry(attempt);
            }
        }
        throw new com.socp.platform.error.exception.ApiException(503,
                "Tenant catalog changed concurrently; retry the operation");
    }

    private static void pauseBeforeRetry(int attempt) {
        // An immediate retry can repeatedly collide with the still-running
        // winner of the first SERIALIZABLE conflict. Bound and jitter the delay.
        long floorMillis = 20L << attempt;
        long delayMillis = java.util.concurrent.ThreadLocalRandom.current()
                .nextLong(floorMillis, floorMillis * 2 + 1);
        try {
            Thread.sleep(delayMillis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new com.socp.platform.error.exception.ApiException(503,
                    "Tenant catalog mutation interrupted");
        }
    }

    private static boolean retryable(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof java.sql.SQLException sql
                    && ("40001".equals(sql.getSQLState()) || "40P01".equals(sql.getSQLState())
                        || "23505".equals(sql.getSQLState()))) return true;
        }
        return false;
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

    @Transactional(readOnly = true)
    List<StoredEntry> findMany(String catalogType, String tenantId, java.util.Collection<String> itemIds) {
        return repository.findByCatalogTypeAndTenantIdAndItemIdIn(catalogType, tenantId, itemIds)
                .stream().map(entry -> new StoredEntry(entry.getItemId(), entry.getPayload(), entry.isDeleted()))
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
    void delete(String catalogType, String tenantId, String itemId, boolean template) {
        TenantCatalogEntry entry = repository.findByCatalogTypeAndTenantIdAndItemId(catalogType, tenantId, itemId)
                .orElse(null);
        if (!template) {
            if (entry != null) repository.delete(entry);
            return;
        }
        if (entry == null) entry = new TenantCatalogEntry(catalogType, tenantId, itemId);
        entry.markDeleted();
        repository.save(entry);
    }

    record StoredEntry(String itemId, String payload, boolean deleted) {
    }
}
