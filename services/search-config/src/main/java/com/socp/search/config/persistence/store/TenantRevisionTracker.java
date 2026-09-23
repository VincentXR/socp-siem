package com.socp.search.config.persistence.store;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Bounded local change tokens; the database remains the catalogue authority. */
final class TenantRevisionTracker {

    private static final int MAX_TENANTS = 4096;
    private final Map<String, Long> revisions = new ConcurrentHashMap<>();
    private long nextRevision;

    long revision(String tenantId) {
        if (tenantId == null) return 0L;
        return revisions.getOrDefault(tenantId, 0L);
    }

    synchronized void bump(String tenantId) {
        if (revisions.size() >= MAX_TENANTS && !revisions.containsKey(tenantId)) {
            revisions.clear();
        }
        revisions.put(tenantId, ++nextRevision);
    }

    int trackedTenants() {
        return revisions.size();
    }
}
