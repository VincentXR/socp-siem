package com.socp.search.config.service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tenant-scoped invalidation for configuration caches on the ingest path.
 *
 * <p>A change token is tracked per tenant, so a write by one tenant cannot flush another
 * tenant's cached entries. Every tenant additionally gets a TTL window: configuration saved
 * through another SEARCH replica has no local token, and without the window it would stay
 * stale on this replica until a local write or a process restart.
 */
final class TenantCacheGuard {

    private static final int MAX_TRACKED_TENANTS = 4096;

    private final Map<String, State> states = new ConcurrentHashMap<>();
    private final long ttlMillis;

    TenantCacheGuard(long ttlMillis) {
        if (ttlMillis < 1) throw new IllegalArgumentException("config cache TTL must be positive");
        this.ttlMillis = ttlMillis;
    }

    /** @return true when the tenant's cached entries must be dropped before they are used */
    boolean isStale(String tenant, long token) {
        State state = states.get(tenant);
        if (state == null) return true;
        return state.token() != token || isExpired(state);
    }

    /** Records the token a refreshed cache was built from. */
    void markFresh(String tenant, long token) {
        if (states.size() >= MAX_TRACKED_TENANTS && !states.containsKey(tenant)) {
            states.clear();
        }
        states.put(tenant, new State(token, System.currentTimeMillis()));
    }

    /** Test seam: the number of tenants currently tracked. */
    int trackedTenants() {
        return states.size();
    }

    private boolean isExpired(State state) {
        return System.currentTimeMillis() - state.startedAt() >= ttlMillis;
    }

    private record State(long token, long startedAt) {
    }
}
