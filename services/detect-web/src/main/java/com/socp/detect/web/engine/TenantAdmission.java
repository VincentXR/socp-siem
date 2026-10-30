package com.socp.detect.web.engine;

import com.socp.rule.model.SecurityEvent;
import com.socp.rule.partition.DetectionRoutingKey;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bounded admission shared by HTTP and Kafka Detection paths.
 *
 * <p>Limits are evaluated independently per tenant: a noisy tenant can be
 * rejected or delayed without consuming another tenant's rate, byte, or
 * active-entity budget.  A permit represents bytes currently owned by the
 * rule engine and must be released when the completion future finishes.</p>
 */
public final class TenantAdmission {

    private final java.util.function.LongSupplier clock;

    public TenantAdmission() { this(System::nanoTime); }

    TenantAdmission(java.util.function.LongSupplier clock) {
        this.clock = java.util.Objects.requireNonNull(clock);
    }

    private volatile Limits limits = Limits.defaults();
    private final Map<String, TenantState> tenants = new ConcurrentHashMap<>();

    public void configure(long eventsPerSecond, long burst, long maxPendingBytes,
                          int maxActiveEntities, long entityIdleTtlMs) {
        limits = new Limits(Math.max(0L, eventsPerSecond),
                Math.max(1L, burst), Math.max(0L, maxPendingBytes),
                Math.max(0, maxActiveEntities),
                Duration.ofMillis(Math.max(1_000L, entityIdleTtlMs)));
    }

    public Decision tryAcquire(SecurityEvent event, long estimatedBytes) {
        if (event == null) return Decision.rejected(RejectionReason.INVALID_EVENT);
        String tenant = event.requireTenantId();
        String entity = DetectionRoutingKey.forEvent(event);
        long bytes = Math.max(1L, estimatedBytes);
        Limits configured = limits;
        TenantState state = tenants.computeIfAbsent(tenant, ignored -> new TenantState(configured, clock.getAsLong()));
        synchronized (state) {
            long now = clock.getAsLong();
            state.pruneEntities(now, configured.entityIdleTtl().toNanos());
            state.refill(now, configured);

            boolean newEntity = !state.entities.containsKey(entity);
            if (configured.maxActiveEntities() > 0 && newEntity
                    && state.entities.size() >= configured.maxActiveEntities()) {
                state.entityRejections++;
                return Decision.rejected(RejectionReason.ACTIVE_ENTITIES);
            }
            // Permit one oversized item when the tenant is otherwise idle. It
            // follows the same progress rule as the Kafka partition budget.
            if (configured.maxPendingBytes() > 0
                    && state.pendingBytes > 0
                    && safeAdd(state.pendingBytes, bytes) > configured.maxPendingBytes()) {
                state.byteRejections++;
                return Decision.rejected(RejectionReason.PENDING_BYTES);
            }
            if (configured.eventsPerSecond() > 0 && state.tokens < 1.0) {
                state.rateRejections++;
                return Decision.rejected(RejectionReason.RATE);
            }

            if (configured.eventsPerSecond() > 0) state.tokens -= 1.0;
            state.pendingBytes = safeAdd(state.pendingBytes, bytes);
            // Keep expiry traversal ordered by the last successful admission.
            Long previousTouch = state.entities.remove(entity);
            state.entities.put(entity, now);
            return Decision.admitted(new Permit(tenant, entity, previousTouch, now, bytes,
                    configured.eventsPerSecond() > 0, state));
        }
    }

    public void release(Permit permit) {
        if (permit == null) return;
        TenantState state = permit.state;
        synchronized (state) {
            if (permit.settled) return;
            permit.settled = true;
            state.pendingBytes = Math.max(0L, state.pendingBytes - permit.bytes());
        }
    }

    /** Undo an admission which could not enter the RuleEngine queue. */
    public void rollback(Permit permit) {
        if (permit == null) return;
        TenantState state = permit.state;
        synchronized (state) {
            if (permit.settled) return;
            permit.settled = true;
            state.pendingBytes = Math.max(0L, state.pendingBytes - permit.bytes());
            if (permit.rateCharged) {
                Limits configured = limits;
                state.refill(clock.getAsLong(), configured);
                state.tokens = Math.min(configured.burst(), state.tokens + 1.0);
            }
            if (state.entities.get(permit.entity()) != null
                    && state.entities.get(permit.entity()) == permit.entityTouchNanos()) {
                if (permit.previousEntityTouch == null) state.entities.remove(permit.entity());
                else state.restoreTouch(permit.entity(), permit.previousEntityTouch);
            }
        }
    }

    /** Restore chained entity touches from the end of an unqueued batch. */
    public void rollbackBatch(java.util.List<Permit> permits) {
        for (int index = permits.size() - 1; index >= 0; index--) rollback(permits.get(index));
    }

    public Map<String, Object> stats(String tenant) {
        TenantState state = tenant == null ? null : tenants.get(tenant);
        if (state == null) {
            return Map.of("pendingBytes", 0L, "activeEntities", 0,
                    "ratePerSecond", limits.eventsPerSecond(),
                    "maxPendingBytes", limits.maxPendingBytes(),
                    "maxActiveEntities", limits.maxActiveEntities());
        }
        synchronized (state) {
            state.pruneEntities(clock.getAsLong(), limits.entityIdleTtl().toNanos());
            return Map.of("pendingBytes", state.pendingBytes,
                    "activeEntities", state.entities.size(),
                    "ratePerSecond", limits.eventsPerSecond(),
                    "maxPendingBytes", limits.maxPendingBytes(),
                    "maxActiveEntities", limits.maxActiveEntities());
        }
    }

    public Map<String, Object> rejectionStats(String tenant) {
        TenantState state = tenant == null ? null : tenants.get(tenant);
        if (state == null) return Map.of();
        synchronized (state) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("rate", state.rateRejections);
            out.put("pendingBytes", state.byteRejections);
            out.put("activeEntities", state.entityRejections);
            out.put("invalid", state.invalidRejections);
            return out;
        }
    }

    public void clear() {
        tenants.clear();
    }

    public static long estimateBytes(SecurityEvent event) {
        if (event == null) return 1L;
        long total = 128L;
        total = safeAdd(total, utf8(event.id()));
        total = safeAdd(total, utf8(event.source()));
        total = safeAdd(total, utf8(event.host()));
        total = safeAdd(total, utf8(event.raw()));
        if (event.fields() != null) {
            for (Map.Entry<String, String> entry : event.fields().entrySet()) {
                total = safeAdd(total, utf8(entry.getKey()));
                total = safeAdd(total, utf8(entry.getValue()));
            }
        }
        return total;
    }

    private static long utf8(String value) {
        return value == null ? 0L : value.getBytes(StandardCharsets.UTF_8).length;
    }

    private static long safeAdd(long left, long right) {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
    }

    public static final class Permit {
        private final String tenant;
        private final String entity;
        private final Long previousEntityTouch;
        private final long entityTouchNanos;
        private final long bytes;
        private final boolean rateCharged;
        private final TenantState state;
        // Accessed only while holding the exact state that issued this permit.
        private boolean settled;

        private Permit(String tenant, String entity, Long previousEntityTouch,
                       long entityTouchNanos, long bytes, boolean rateCharged, TenantState state) {
            this.tenant = tenant;
            this.entity = entity;
            this.previousEntityTouch = previousEntityTouch;
            this.entityTouchNanos = entityTouchNanos;
            this.bytes = bytes;
            this.rateCharged = rateCharged;
            this.state = state;
        }

        public String tenant() { return tenant; }
        public String entity() { return entity; }
        public boolean newEntity() { return previousEntityTouch == null; }
        public long entityTouchNanos() { return entityTouchNanos; }
        public long bytes() { return bytes; }
    }

    public record Decision(Permit permit, RejectionReason reason) {
        public boolean admitted() {
            return permit != null;
        }

        static Decision admitted(Permit permit) {
            return new Decision(permit, null);
        }

        static Decision rejected(RejectionReason reason) {
            return new Decision(null, reason);
        }
    }

    public enum RejectionReason {
        RATE,
        PENDING_BYTES,
        ACTIVE_ENTITIES,
        INVALID_EVENT
    }

    /** Transient admission failure; Kafka callers must leave the offset pending. */
    public static final class RejectedException extends RuntimeException {
        private final String tenant;
        private final RejectionReason reason;

        public RejectedException(String tenant, RejectionReason reason) {
            super("tenant admission rejected tenant=" + tenant + " reason=" + reason);
            this.tenant = tenant;
            this.reason = reason;
        }

        public String tenant() {
            return tenant;
        }

        public RejectionReason reason() {
            return reason;
        }
    }

    private record Limits(long eventsPerSecond, long burst, long maxPendingBytes,
                          int maxActiveEntities, Duration entityIdleTtl) {
        static Limits defaults() {
            return new Limits(0L, 100L, 64L * 1024 * 1024, 100_000,
                    Duration.ofMinutes(30));
        }
    }

    private static final class TenantState {
        private double tokens;
        private long lastRefillNanos;
        private long pendingBytes;
        // Every access is protected by this TenantState's monitor.
        private final Map<String, Long> entities = new LinkedHashMap<>();
        private long rateRejections;
        private long byteRejections;
        private long entityRejections;
        private long invalidRejections;

        private TenantState(Limits limits, long now) {
            this.tokens = limits.burst();
            this.lastRefillNanos = now;
        }

        private void refill(long now, Limits limits) {
            if (limits.eventsPerSecond() <= 0) return;
            long elapsed = Math.max(0L, now - lastRefillNanos);
            tokens = Math.min(limits.burst(), tokens
                    + elapsed / 1_000_000_000.0 * limits.eventsPerSecond());
            lastRefillNanos = now;
        }

        /** Rollback is rare; restore the expiry order without making successful admission scan the map. */
        private void restoreTouch(String entity, long previousTouch) {
            Map<String, Long> restored = new LinkedHashMap<>();
            boolean inserted = false;
            for (var entry : entities.entrySet()) {
                if (entry.getKey().equals(entity)) continue;
                if (!inserted && entry.getValue() > previousTouch) {
                    restored.put(entity, previousTouch);
                    inserted = true;
                }
                restored.put(entry.getKey(), entry.getValue());
            }
            if (!inserted) restored.put(entity, previousTouch);
            entities.clear();
            entities.putAll(restored);
        }

        private void pruneEntities(long now, long idleNanos) {
            var iterator = entities.entrySet().iterator();
            while (iterator.hasNext()) {
                if (now - iterator.next().getValue() <= idleNanos) break;
                iterator.remove();
            }
        }
    }
}
