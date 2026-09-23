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

    public static Set<String> put(String tenantId, String name, Collection<String> values) {
        return write(tenantId, name, values, false);
    }

    /** Create only when the effective list is absent, including inherited templates. */
    public static Set<String> create(String tenantId, String name, Collection<String> values) {
        return write(tenantId, name, values, true);
    }

    private static Set<String> write(String tenantId, String name, Collection<String> values, boolean createOnly) {
        String tenant = normalizeTenant(tenantId);
        String normalizedName = normalizeName(name);
        WatchlistLimits.name(normalizedName);
        Set<String> normalized = normalizedValues(values);
        WatchlistLimits.values(normalized);
        return stateStore.update(tenant, normalizedName, current -> {
            if (createOnly && (current == null ? hasTemplate(normalizedName) : !current.deleted())) {
                throw new AlreadyExistsException();
            }
            return new WatchlistStateStore.State(normalized, false);
        }).values();
    }

    public static final class AlreadyExistsException extends RuntimeException {
        public AlreadyExistsException() { super("watchlist already exists"); }
    }

    public static void put(String name, Collection<String> values) {
        put(DEFAULT_TENANT, name, values);
    }

    public static Set<String> add(String tenantId, String name, Collection<String> values) {
        if (values == null) return values(tenantId, name);
        String tenant = normalizeTenant(tenantId);
        String normalizedName = normalizeName(name);
        WatchlistLimits.name(normalizedName);
        Set<String> additions = normalizedValues(values);
        WatchlistLimits.values(additions);
        return stateStore.update(tenant, normalizedName, current -> {
            Set<String> inherited = TEMPLATES.getOrDefault(normalizedName, Set.of());
            Set<String> result = new LinkedHashSet<>(current == null ? inherited
                    : current.deleted() ? Set.of() : current.values());
            result.addAll(additions);
            WatchlistLimits.values(result);
            return new WatchlistStateStore.State(result, false);
        }).values();
    }

    public static void add(String name, Collection<String> values) {
        add(DEFAULT_TENANT, name, values);
    }

    public static boolean delete(String tenantId, String name) {
        String tenant = normalizeTenant(tenantId);
        String normalizedName = normalizeName(name);
        WatchlistLimits.name(normalizedName);
        java.util.concurrent.atomic.AtomicBoolean existed = new java.util.concurrent.atomic.AtomicBoolean();
        stateStore.update(tenant, normalizedName, current -> {
            existed.set(current == null ? TEMPLATES.containsKey(normalizedName) : !current.deleted());
            return new WatchlistStateStore.State(Set.of(), true);
        });
        return existed.get();
    }

    public static boolean delete(String name) {
        return delete(DEFAULT_TENANT, name);
    }

    public static boolean contains(String tenantId, String name, String value) {
        if (value == null) return false;
        return values(tenantId, name).contains(value.trim().toLowerCase(java.util.Locale.ROOT));
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

    public static boolean hasTemplate(String name) {
        return TEMPLATES.containsKey(name);
    }

    public static Set<String> values(String tenantId, String name) {
        String tenant = normalizeTenant(tenantId);
        String normalizedName = normalizeName(name);
        if (normalizedName == null) return Set.of();
        WatchlistStateStore.State state;
        try {
            state = stateStore.find(tenant, normalizedName);
        } catch (RuleDependencyException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            // Includes transaction-proxy failures and unreadable persisted data.
            // Neither is evidence that the evaluating rule itself is invalid.
            throw new RuleDependencyException("Unable to read required watchlist state", failure);
        }
        if (state != null) {
            if (state.deleted()) return Set.of();
            return state.values();
        }
        Set<String> values = TEMPLATES.get(normalizedName);
        return values == null ? Set.of() : values;
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
        // Tests may install a Mockito-backed durable store. Restore the
        // process-local default so a following test cannot observe that mock.
        stateStore = new InMemoryStateStore();
    }

    private static String normalizeTenant(String tenantId) {
        return tenantId == null || tenantId.isBlank() ? DEFAULT_TENANT : tenantId.trim();
    }

    private static String normalizeName(String name) {
        return name == null || name.isBlank() ? null : name.trim().toLowerCase(java.util.Locale.ROOT);
    }

    private static Set<String> normalizedValues(Collection<String> values) {
        if (values != null) WatchlistLimits.values(values);
        Set<String> result = new LinkedHashSet<>();
        if (values != null) {
            for (String value : values) {
                if (value != null && !value.isBlank()) result.add(value.trim().toLowerCase(java.util.Locale.ROOT));
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
        public State update(String tenantId, String name, java.util.function.UnaryOperator<State> mutation) {
            Map<String, State> tenant = states.computeIfAbsent(tenantId, ignored -> new ConcurrentHashMap<>());
            synchronized (tenant) {
                State next = mutation.apply(tenant.get(name));
                WatchlistLimits.values(next.values());
                if (next.deleted() && !hasTemplate(name)) {
                    tenant.remove(name);
                    return next;
                }
                if (!tenant.containsKey(name) && tenant.size() >= WatchlistLimits.MAX_LISTS) {
                    throw new IllegalArgumentException("watchlist namespace limit reached");
                }
                tenant.put(name, next);
                return next;
            }
        }

        @Override
        public void clear() {
            states.clear();
        }
    }
}
