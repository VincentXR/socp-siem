package com.socp.rule.engine;

import java.util.Set;

/**
 * Storage contract for tenant-owned watchlist overlays.  The rule module keeps
 * packaged templates and normalization rules; an application can install a
 * durable implementation without making the rule engine depend on Spring or
 * a particular database client.
 */
public interface WatchlistStateStore {

    /** Returns an owned overlay/tombstone, or {@code null} when none exists. */
    State find(String tenantId, String name);

    /** Names with a tenant-owned overlay or tombstone. */
    Set<String> names(String tenantId);

    void save(String tenantId, String name, Set<String> values);

    void delete(String tenantId, String name);

    /** Intended for isolated tests only. */
    void clear();

    record State(Set<String> values, boolean deleted) {
        public State {
            values = values == null ? Set.of() : Set.copyOf(values);
        }
    }
}
