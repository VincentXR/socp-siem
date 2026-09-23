package com.socp.search.config.persistence.store;

import com.socp.platform.error.exception.ApiException;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;

/** Bounded tenant metadata with database-owned create/update/delete decisions. */
final class MetadataCatalogPolicy<T> {
    private final TenantCatalog<T> catalog;
    private final TenantCatalogPersistence persistence;
    private final Function<T, String> id;
    private final Function<T, String> key;
    private final String builtinPrefix;
    private final int maximum;
    private final boolean caseInsensitiveKey;

    MetadataCatalogPolicy(TenantCatalog<T> catalog, TenantCatalogPersistence persistence,
                          Function<T, String> id, Function<T, String> key,
                          String builtinPrefix, int maximum, boolean caseInsensitiveKey) {
        this.catalog = catalog;
        this.persistence = persistence;
        this.id = id;
        this.key = key;
        this.builtinPrefix = builtinPrefix;
        this.maximum = maximum;
        this.caseInsensitiveKey = caseInsensitiveKey;
    }

    List<T> list() {
        List<T> effective = catalog.list();
        Set<String> legacyKeys = effective.stream()
                .filter(item -> !id.apply(item).startsWith(builtinPrefix))
                .map(item -> normalized(key.apply(item))).collect(Collectors.toSet());
        return effective.stream()
                .filter(item -> !id.apply(item).startsWith(builtinPrefix)
                        || !legacyKeys.contains(normalized(key.apply(item))))
                .toList();
    }

    T save(T value) {
        return mutate(() -> {
            if (catalog.get(id.apply(value)) != null) {
                throw new ApiException(409, "Metadata ID already exists; use update");
            }
            List<T> current = list();
            if (current.stream().anyMatch(item -> Objects.equals(
                    normalized(key.apply(item)), normalized(key.apply(value))))) {
                throw new ApiException(409, "Metadata identifier already exists");
            }
            if (current.size() >= maximum) {
                throw ApiException.badRequest("Tenant metadata catalogue limit exceeded: " + maximum);
            }
            return catalog.save(value);
        });
    }

    T update(String itemId, UnaryOperator<T> edit) {
        return mutate(() -> {
            T existing = catalog.get(itemId);
            if (existing == null) throw ApiException.notFound("Metadata item not found: " + itemId);
            T updated = edit.apply(existing);
            if (!Objects.equals(id.apply(updated), itemId)
                    || !Objects.equals(normalized(key.apply(existing)), normalized(key.apply(updated)))) {
                throw new ApiException(409, "Metadata identifier cannot be changed");
            }
            return catalog.save(updated);
        });
    }

    boolean delete(String itemId, Consumer<T> validate) {
        return mutate(() -> {
            T existing = catalog.get(itemId);
            if (existing == null) return false;
            validate.accept(existing);
            return catalog.delete(itemId);
        });
    }

    private String normalized(String value) {
        return caseInsensitiveKey && value != null ? value.toUpperCase(Locale.ROOT) : value;
    }

    private <R> R mutate(java.util.function.Supplier<R> operation) {
        if (persistence != null) return persistence.mutate(operation);
        synchronized (catalog) { return operation.get(); }
    }
}
