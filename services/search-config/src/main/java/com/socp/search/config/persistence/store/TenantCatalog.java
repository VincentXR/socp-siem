package com.socp.search.config.persistence.store;



import com.socp.search.config.persistence.store.*;
import com.socp.search.config.persistence.repository.*;
import com.socp.search.config.persistence.entity.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.socp.platform.tenant.context.TenantContext;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/** In-memory tenant overlay on top of immutable built-in catalog entries. */
final class TenantCatalog<T> {

    private final Function<T, String> id;
    private final String catalogType;
    private final Class<T> valueType;
    private final TenantCatalogPersistence persistence;
    private final ObjectMapper objectMapper;
    private final Map<String, T> templates = new LinkedHashMap<>();
    private final Map<String, Map<String, T>> overlays = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> deleted = new ConcurrentHashMap<>();

    TenantCatalog(Function<T, String> id) {
        this(id, null, null, null, null);
    }

    TenantCatalog(Function<T, String> id, String catalogType, Class<T> valueType,
                  TenantCatalogPersistence persistence, ObjectMapper objectMapper) {
        this.id = id;
        this.catalogType = catalogType;
        this.valueType = valueType;
        this.persistence = persistence;
        this.objectMapper = objectMapper;
    }

    synchronized void registerTemplate(T value) {
        templates.put(id.apply(value), value);
    }

    T save(T value) {
        String tenant = tenant();
        String key = id.apply(value);
        if (persistence != null) {
            persistence.save(catalogType, tenant, key, serialize(value));
            return value;
        }
        overlays.computeIfAbsent(tenant, ignored -> new ConcurrentHashMap<>()).put(key, value);
        deleted.computeIfAbsent(tenant, ignored -> ConcurrentHashMap.newKeySet()).remove(key);
        return value;
    }

    T get(String key) {
        String tenant = tenant();
        if (persistence != null) {
            TenantCatalogPersistence.StoredEntry entry = persistence.find(catalogType, tenant, key);
            if (entry != null) return entry.deleted() ? null : deserialize(entry.payload());
            return templates.get(key);
        }
        if (deleted.getOrDefault(tenant, Set.of()).contains(key)) return null;
        T override = overlays.getOrDefault(tenant, Map.of()).get(key);
        return override == null ? templates.get(key) : override;
    }

    List<T> list() {
        String tenant = tenant();
        Map<String, T> effective = new LinkedHashMap<>(templates);
        if (persistence != null) {
            for (TenantCatalogPersistence.StoredEntry entry : persistence.list(catalogType, tenant)) {
                if (entry.deleted()) effective.remove(entry.itemId());
                else effective.put(entry.itemId(), deserialize(entry.payload()));
            }
            return List.copyOf(new ArrayList<>(effective.values()));
        }
        effective.putAll(overlays.getOrDefault(tenant, Map.of()));
        effective.keySet().removeAll(deleted.getOrDefault(tenant, Set.of()));
        return List.copyOf(new ArrayList<>(effective.values()));
    }

    boolean delete(String key) {
        String tenant = tenant();
        boolean existed = get(key) != null;
        if (persistence != null) {
            persistence.delete(catalogType, tenant, key);
            return existed;
        }
        Map<String, T> tenantOverlay = overlays.get(tenant);
        if (tenantOverlay != null) tenantOverlay.remove(key);
        deleted.computeIfAbsent(tenant, ignored -> ConcurrentHashMap.newKeySet()).add(key);
        return existed;
    }

    private static String tenant() {
        String tenant = TenantContext.get();
        return tenant == null || tenant.isBlank() ? "default" : tenant;
    }

    private String serialize(T value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception failure) {
            throw new IllegalStateException("Unable to persist tenant catalog entry", failure);
        }
    }

    private T deserialize(String value) {
        try {
            return objectMapper.readValue(value, valueType);
        } catch (Exception failure) {
            throw new IllegalStateException("Unable to read tenant catalog entry", failure);
        }
    }
}
