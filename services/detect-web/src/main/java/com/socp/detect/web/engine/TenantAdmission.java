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
        TenantState state = tenants.computeIfAbsent(tenant, ignored -> new TenantState(configured));
        synchronized (state) {
            long now = System.nanoTime();
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
            state.entities.put(entity, now);
            return Decision.admitted(new Permit(tenant, entity, newEntity, now, bytes));
        }
    }

    public void release(Permit permit) {
        if (permit == null) return;
        TenantState state = tenants.get(permit.tenant());
        if (state == null) return;
        synchronized (state) {
            state.pendingBytes = Math.max(0L, state.pendingBytes - permit.bytes());
        }
    }

    /** Undo an admission which could not enter the RuleEngine queue. */
    public void rollback(Permit permit) {
        if (permit == null) return;
        TenantState state = tenants.get(permit.tenant());
        if (state == null) return;
        synchronized (state) {
            state.pendingBytes = Math.max(0L, state.pendingBytes - permit.bytes());
            if (permit.newEntity() && state.entities.get(permit.entity()) != null
                    && state.entities.get(permit.entity()) == permit.entityTouchNanos()) {
                state.entities.remove(permit.entity());
            }
        }
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
            state.pruneEntities(System.nanoTime(), limits.entityIdleTtl().toNanos());
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

    public record Permit(String tenant, String entity, boolean newEntity,
                         long entityTouchNanos, long bytes) {
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
        private final Map<String, Long> entities = new ConcurrentHashMap<>();
        private long rateRejections;
        private long byteRejections;
        private long entityRejections;
        private long invalidRejections;

        private TenantState(Limits limits) {
            this.tokens = limits.burst();
            this.lastRefillNanos = System.nanoTime();
        }

        private void refill(long now, Limits limits) {
            if (limits.eventsPerSecond() <= 0) return;
            long elapsed = Math.max(0L, now - lastRefillNanos);
            tokens = Math.min(limits.burst(), tokens
                    + elapsed / 1_000_000_000.0 * limits.eventsPerSecond());
            lastRefillNanos = now;
        }

        private void pruneEntities(long now, long idleNanos) {
            entities.entrySet().removeIf(entry -> now - entry.getValue() > idleNanos);
        }
    }
}
