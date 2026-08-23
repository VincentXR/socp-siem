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
    private static volatile WatchlistStateStore stateStore = new InMemoryStateStore();

    private Watchlists() {
    }

    /**
     * Installs a durable backing store for tenant mutations. Packaged templates
     * remain in this module so rules retain the same inherited-list semantics.
     */
    public static void installStateStore(WatchlistStateStore store) {
        if (store == null) throw new IllegalArgumentException("watchlist state store is required");
        stateStore = store;
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
        stateStore.save(tenant, normalizedName, normalizedValues(values));
    }

    public static void put(String name, Collection<String> values) {
        put(DEFAULT_TENANT, name, values);
    }

    public static void add(String tenantId, String name, Collection<String> values) {
        if (values == null) return;
        String tenant = normalizeTenant(tenantId);
        String normalizedName = normalizeName(name);
        if (normalizedName == null) return;
        Set<String> result = new LinkedHashSet<>(values(tenant, normalizedName));
        result.addAll(normalizedValues(values));
        stateStore.save(tenant, normalizedName, result);
    }

    public static void add(String name, Collection<String> values) {
        add(DEFAULT_TENANT, name, values);
    }

    public static boolean delete(String tenantId, String name) {
        String tenant = normalizeTenant(tenantId);
        String normalizedName = normalizeName(name);
        if (normalizedName == null) return false;
        boolean existed = names(tenant).contains(normalizedName);
        stateStore.delete(tenant, normalizedName);
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
        for (String name : stateStore.names(tenant)) {
            WatchlistStateStore.State state = stateStore.find(tenant, name);
            if (state == null || state.deleted()) result.remove(name);
            else result.add(name);
        }
        return Collections.unmodifiableSet(result);
    }

    public static Set<String> names() {
        return names(DEFAULT_TENANT);
    }

    public static Set<String> values(String tenantId, String name) {
        String tenant = normalizeTenant(tenantId);
        String normalizedName = normalizeName(name);
        if (normalizedName == null) return Set.of();
        WatchlistStateStore.State state = stateStore.find(tenant, normalizedName);
        if (state != null) {
            if (state.deleted()) return Set.of();
            return Collections.unmodifiableSet(new LinkedHashSet<>(state.values()));
        }
        Set<String> values = TEMPLATES.get(normalizedName);
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
        stateStore.clear();
    }

    private static String normalizeTenant(String tenantId) {
        return tenantId == null || tenantId.isBlank() ? DEFAULT_TENANT : tenantId.trim();
    }

    private static String normalizeName(String name) {
        return name == null || name.isBlank() ? null : name.trim().toLowerCase();
    }

    private static Set<String> normalizedValues(Collection<String> values) {
        Set<String> result = new LinkedHashSet<>();
        if (values != null) {
            for (String value : values) {
                if (value != null && !value.isBlank()) result.add(value.trim().toLowerCase());
            }
        }
        return Collections.unmodifiableSet(result);
    }

    private static final class InMemoryStateStore implements WatchlistStateStore {
        private final Map<String, Map<String, State>> states = new ConcurrentHashMap<>();

        @Override
        public State find(String tenantId, String name) {
            return states.getOrDefault(tenantId, Map.of()).get(name);
        }

        @Override
        public Set<String> names(String tenantId) {
            return Set.copyOf(states.getOrDefault(tenantId, Map.of()).keySet());
        }

        @Override
        public void save(String tenantId, String name, Set<String> values) {
            states.computeIfAbsent(tenantId, ignored -> new ConcurrentHashMap<>())
                    .put(name, new State(values, false));
        }

        @Override
        public void delete(String tenantId, String name) {
            states.computeIfAbsent(tenantId, ignored -> new ConcurrentHashMap<>())
                    .put(name, new State(Set.of(), true));
        }

        @Override
        public void clear() {
            states.clear();
        }
    }
}
