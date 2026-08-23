package com.socp.rule.engine;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tenant-scoped watchlists used by rule conditions.
 *
 * <p>Built-in templates are inherited until a tenant replaces or deletes a
 * list. Tenant mutations never change another tenant's effective values.</p>
 */
public final class Watchlists {

    private static final String DEFAULT_TENANT = "default";
    private static final Map<String, Set<String>> TEMPLATES = new ConcurrentHashMap<>();
    private static final Map<String, Map<String, Set<String>>> LISTS = new ConcurrentHashMap<>();
    private static final Map<String, Set<String>> DELETED = new ConcurrentHashMap<>();

    private Watchlists() {
    }

    /** Register an inherited built-in list without making it tenant-owned. */
    public static void putTemplate(String name, Collection<String> values) {
        String normalizedName = normalizeName(name);
        if (normalizedName == null) return;
        TEMPLATES.put(normalizedName, normalizedValues(values));
    }

    public static void put(String tenantId, String name, Collection<String> values) {
        String tenant = normalizeTenant(tenantId);
        String normalizedName = normalizeName(name);
        if (normalizedName == null) return;
        LISTS.computeIfAbsent(tenant, ignored -> new ConcurrentHashMap<>())
                .put(normalizedName, normalizedValues(values));
        DELETED.computeIfAbsent(tenant, ignored -> ConcurrentHashMap.newKeySet())
                .remove(normalizedName);
    }

    public static void put(String name, Collection<String> values) {
        put(DEFAULT_TENANT, name, values);
    }

    public static void add(String tenantId, String name, Collection<String> values) {
        if (values == null) return;
        String tenant = normalizeTenant(tenantId);
        String normalizedName = normalizeName(name);
        if (normalizedName == null) return;
        Map<String, Set<String>> tenantLists = LISTS.computeIfAbsent(tenant,
                ignored -> new ConcurrentHashMap<>());
        tenantLists.compute(normalizedName, (ignored, existing) -> {
            Set<String> result = ConcurrentHashMap.newKeySet();
            if (existing != null) result.addAll(existing);
            else if (!isDeleted(tenant, normalizedName)) {
                result.addAll(TEMPLATES.getOrDefault(normalizedName, Set.of()));
            }
            result.addAll(normalizedValues(values));
            return result;
        });
        DELETED.computeIfAbsent(tenant, ignored -> ConcurrentHashMap.newKeySet())
                .remove(normalizedName);
    }

    public static void add(String name, Collection<String> values) {
        add(DEFAULT_TENANT, name, values);
    }

    public static boolean delete(String tenantId, String name) {
        String tenant = normalizeTenant(tenantId);
        String normalizedName = normalizeName(name);
        if (normalizedName == null) return false;
        boolean existed = names(tenant).contains(normalizedName);
        Map<String, Set<String>> tenantLists = LISTS.get(tenant);
        if (tenantLists != null) tenantLists.remove(normalizedName);
        DELETED.computeIfAbsent(tenant, ignored -> ConcurrentHashMap.newKeySet())
                .add(normalizedName);
        return existed;
    }

    public static boolean delete(String name) {
        return delete(DEFAULT_TENANT, name);
    }

    public static boolean contains(String tenantId, String name, String value) {
        if (value == null) return false;
        return values(tenantId, name).contains(value.trim().toLowerCase());
    }

    public static boolean contains(String name, String value) {
        return contains(DEFAULT_TENANT, name, value);
    }

    public static Set<String> names(String tenantId) {
        String tenant = normalizeTenant(tenantId);
        Set<String> result = new LinkedHashSet<>(TEMPLATES.keySet());
        Map<String, Set<String>> tenantLists = LISTS.get(tenant);
        if (tenantLists != null) result.addAll(tenantLists.keySet());
        result.removeAll(DELETED.getOrDefault(tenant, Set.of()));
        return Collections.unmodifiableSet(result);
    }

    public static Set<String> names() {
        return names(DEFAULT_TENANT);
    }

    public static Set<String> values(String tenantId, String name) {
        String tenant = normalizeTenant(tenantId);
        String normalizedName = normalizeName(name);
        if (normalizedName == null || isDeleted(tenant, normalizedName)) return Set.of();
        Map<String, Set<String>> tenantLists = LISTS.get(tenant);
        Set<String> values = tenantLists == null ? null : tenantLists.get(normalizedName);
        if (values == null) values = TEMPLATES.get(normalizedName);
        return values == null ? Set.of()
                : Collections.unmodifiableSet(new LinkedHashSet<>(values));
    }

    public static Set<String> values(String name) {
        return values(DEFAULT_TENANT, name);
    }

    public static int size(String tenantId, String name) {
        return values(tenantId, name).size();
    }

    public static int size(String name) {
        return size(DEFAULT_TENANT, name);
    }

    /** Only for tests. */
    public static void clear() {
        TEMPLATES.clear();
        LISTS.clear();
        DELETED.clear();
    }

    private static boolean isDeleted(String tenant, String name) {
        return DELETED.getOrDefault(tenant, Set.of()).contains(name);
    }

    private static String normalizeTenant(String tenantId) {
        return tenantId == null || tenantId.isBlank() ? DEFAULT_TENANT : tenantId.trim();
    }

    private static String normalizeName(String name) {
        return name == null || name.isBlank() ? null : name.trim().toLowerCase();
    }

    private static Set<String> normalizedValues(Collection<String> values) {
        Set<String> result = ConcurrentHashMap.newKeySet();
        if (values != null) {
            for (String value : values) {
                if (value != null && !value.isBlank()) result.add(value.trim().toLowerCase());
            }
        }
        return result;
    }
}
