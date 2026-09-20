package com.socp.detect.web.routing;

import com.socp.detect.web.persistence.store.RuleSpecStore;
import com.socp.platform.tenant.context.TenantContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Small TTL cache for executable tenant routing plans. */
@Component
public class DetectionRoutingPlanRegistry {

    private final RuleSpecStore store;
    private final int maxDimensions;
    private final long refreshNanos;
    private final ConcurrentHashMap<String, Entry> plans = new ConcurrentHashMap<>();

    public DetectionRoutingPlanRegistry(
            RuleSpecStore store,
            @Value("${socp.detect.routing.max-stateful-dimensions:8}") int maxDimensions,
            @Value("${socp.detect.routing.plan-refresh-ms:1000}") long refreshMs) {
        this.store = store;
        this.maxDimensions = Math.max(1, Math.min(32, maxDimensions));
        this.refreshNanos = Duration.ofMillis(Math.max(100L, Math.min(60_000L, refreshMs))).toNanos();
    }

    public DetectionRoutingPlan plan(String tenant) {
        String resolved = tenant == null || tenant.isBlank() ? "default" : tenant;
        long now = System.nanoTime();
        Entry current = plans.get(resolved);
        if (current != null && now - current.loadedAtNanos() < refreshNanos) return current.plan();
        DetectionRoutingPlan loaded = TenantContext.callWith(resolved,
                () -> DetectionRoutingPlan.compile(store.list(resolved), maxDimensions));
        plans.put(resolved, new Entry(loaded, now));
        return loaded;
    }

    public void invalidate(String tenant) {
        if (tenant == null || tenant.isBlank()) plans.clear();
        else plans.remove(tenant);
    }

    public Map<String, DetectionRoutingPlan> knownPlans() {
        Map<String, DetectionRoutingPlan> out = new java.util.LinkedHashMap<>();
        plans.forEach((tenant, entry) -> out.put(tenant, entry.plan()));
        return Map.copyOf(out);
    }

    public boolean hasKnownUnsupportedPlan() {
        return plans.values().stream().map(Entry::plan).anyMatch(plan -> !plan.supported());
    }

    private record Entry(DetectionRoutingPlan plan, long loadedAtNanos) {
    }
}
