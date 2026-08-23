package com.socp.search.config.store;

import com.socp.platform.tenant.TenantContext;

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
    private final Map<String, T> templates = new LinkedHashMap<>();
    private final Map<String, Map<String, T>> overlays = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> deleted = new ConcurrentHashMap<>();

    TenantCatalog(Function<T, String> id) {
        this.id = id;
    }

    synchronized void registerTemplate(T value) {
        templates.put(id.apply(value), value);
    }

    T save(T value) {
        String tenant = tenant();
        String key = id.apply(value);
        overlays.computeIfAbsent(tenant, ignored -> new ConcurrentHashMap<>()).put(key, value);
        deleted.computeIfAbsent(tenant, ignored -> ConcurrentHashMap.newKeySet()).remove(key);
        return value;
    }

    T get(String key) {
        String tenant = tenant();
        if (deleted.getOrDefault(tenant, Set.of()).contains(key)) return null;
        T override = overlays.getOrDefault(tenant, Map.of()).get(key);
        return override == null ? templates.get(key) : override;
    }

    List<T> list() {
        String tenant = tenant();
        Map<String, T> effective = new LinkedHashMap<>(templates);
        effective.putAll(overlays.getOrDefault(tenant, Map.of()));
        effective.keySet().removeAll(deleted.getOrDefault(tenant, Set.of()));
        return List.copyOf(new ArrayList<>(effective.values()));
    }

    boolean delete(String key) {
        String tenant = tenant();
        boolean existed = get(key) != null;
        Map<String, T> tenantOverlay = overlays.get(tenant);
        if (tenantOverlay != null) tenantOverlay.remove(key);
        deleted.computeIfAbsent(tenant, ignored -> ConcurrentHashMap.newKeySet()).add(key);
        return existed;
    }

    private static String tenant() {
        String tenant = TenantContext.get();
        return tenant == null || tenant.isBlank() ? "default" : tenant;
    }
}
