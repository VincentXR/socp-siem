package com.socp.detect.web.engine;

import com.socp.rule.model.SecurityEvent;
import com.socp.rule.model.Severity;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TenantAdmissionTest {

    @Test
    void budgetsAreIndependentPerTenantAndReleasedAfterCompletion() {
        TenantAdmission controller = new TenantAdmission();
        controller.configure(0, 1, 100, 0, 60_000);

        TenantAdmission.Decision first = controller.tryAcquire(event("tenant-a", "host-a"), 80);
        assertTrue(first.admitted());
        assertEquals(TenantAdmission.RejectionReason.PENDING_BYTES,
                controller.tryAcquire(event("tenant-a", "host-b"), 80).reason());
        assertTrue(controller.tryAcquire(event("tenant-b", "host-b"), 80).admitted(),
                "one tenant must not consume another tenant's budget");

        controller.release(first.permit());
        assertTrue(controller.tryAcquire(event("tenant-a", "host-b"), 80).admitted());
    }

    @Test
    void rateBudgetRejectsBurstAndRecordsReason() {
        TenantAdmission controller = new TenantAdmission();
        controller.configure(1, 1, 0, 0, 60_000);

        assertTrue(controller.tryAcquire(event("tenant-a", "host-a"), 1).admitted());
        TenantAdmission.Decision rejected =
                controller.tryAcquire(event("tenant-a", "host-a"), 1);
        assertEquals(TenantAdmission.RejectionReason.RATE, rejected.reason());
        assertEquals(1L, controller.rejectionStats("tenant-a").get("rate"));
    }

    @Test
    void activeEntityBudgetIsIndependentPerTenant() {
        TenantAdmission controller = new TenantAdmission();
        controller.configure(0, 1, 0, 1, 60_000);

        assertTrue(controller.tryAcquire(event("tenant-a", "host-a"), 1).admitted());
        assertEquals(TenantAdmission.RejectionReason.ACTIVE_ENTITIES,
                controller.tryAcquire(event("tenant-a", "host-b"), 1).reason());
        assertTrue(controller.tryAcquire(event("tenant-b", "host-b"), 1).admitted());
    }

    @Test
    void expiryFollowsLastSuccessfulTouchAndRollbackDoesNotRemoveANewerAdmission() {
        var time = new java.util.concurrent.atomic.AtomicLong();
        TenantAdmission controller = new TenantAdmission(time::get);
        controller.configure(0, 1, 0, 2, 1_000);
        var first = controller.tryAcquire(event("tenant-a", "a"), 1);
        time.set(100_000_000L);
        controller.tryAcquire(event("tenant-a", "b"), 1);
        time.set(900_000_000L);
        controller.tryAcquire(event("tenant-a", "a"), 1);
        controller.rollback(first.permit());
        assertEquals(2, controller.stats("tenant-a").get("activeEntities"));
        time.set(1_200_000_000L);
        assertTrue(controller.tryAcquire(event("tenant-a", "c"), 1).admitted(),
                "the expired b must be pruned even after a was refreshed");
        assertEquals(TenantAdmission.RejectionReason.ACTIVE_ENTITIES,
                controller.tryAcquire(event("tenant-a", "d"), 1).reason());
        assertTrue(controller.tryAcquire(event("tenant-a", "a"), 1).admitted());
    }

    @Test
    void abandoningPartialBatchRestoresBurstBytesAndEntityReservations() {
        TenantAdmission controller = new TenantAdmission();
        controller.configure(1, 31, 0, 1, 60_000);
        var permits = new java.util.ArrayList<TenantAdmission.Permit>();
        for (int index = 0; index < 31; index++) {
            var decision = controller.tryAcquire(event("tenant-a", "host-a"), 10);
            assertTrue(decision.admitted());
            permits.add(decision.permit());
        }
        assertEquals(TenantAdmission.RejectionReason.RATE,
                controller.tryAcquire(event("tenant-a", "host-a"), 10).reason());
        controller.rollbackBatch(permits);
        assertEquals(0L, controller.stats("tenant-a").get("pendingBytes"));
        assertEquals(0, controller.stats("tenant-a").get("activeEntities"));
        for (int index = 0; index < 31; index++) {
            var fallback = controller.tryAcquire(event("tenant-a", "host-b"), 10);
            assertTrue(fallback.admitted(), "unqueued prefix must not consume fallback's burst");
            controller.release(fallback.permit());
        }
        controller.rollbackBatch(permits);
        assertEquals(TenantAdmission.RejectionReason.RATE,
                controller.tryAcquire(event("tenant-a", "host-b"), 10).reason(),
                "a second rollback must not manufacture new tokens");
    }

    @Test
    void completionCannotRefundRateAndOldPermitsCannotReleaseNewState() {
        TenantAdmission controller = new TenantAdmission();
        controller.configure(1, 1, 0, 0, 60_000);
        var completed = controller.tryAcquire(event("tenant-a", "host-a"), 10).permit();
        controller.release(completed);
        controller.rollback(completed);
        assertEquals(TenantAdmission.RejectionReason.RATE,
                controller.tryAcquire(event("tenant-a", "host-a"), 10).reason());
        controller.clear();
        var abandoned = controller.tryAcquire(event("tenant-a", "host-a"), 10).permit();
        controller.clear();
        var current = controller.tryAcquire(event("tenant-a", "host-a"), 20).permit();
        controller.rollback(abandoned);
        assertEquals(20L, controller.stats("tenant-a").get("pendingBytes"));
        assertEquals(TenantAdmission.RejectionReason.RATE,
                controller.tryAcquire(event("tenant-a", "host-a"), 1).reason());
        controller.release(current);
    }

    @Test
    void rollingBackARefreshRestoresExpiryOrderAheadOfNewerEntities() {
        var time = new java.util.concurrent.atomic.AtomicLong();
        TenantAdmission controller = new TenantAdmission(time::get);
        controller.configure(0, 1, 0, 2, 1_000);
        controller.release(controller.tryAcquire(event("tenant-a", "a"), 1).permit());
        time.set(100_000_000L);
        controller.release(controller.tryAcquire(event("tenant-a", "b"), 1).permit());
        time.set(900_000_000L);
        controller.rollback(controller.tryAcquire(event("tenant-a", "a"), 1).permit());
        time.set(1_050_000_000L);
        assertEquals(1, controller.stats("tenant-a").get("activeEntities"),
                "the restored older touch must expire before the still-live newer entity");
        assertTrue(controller.tryAcquire(event("tenant-a", "c"), 1).admitted());
    }

    private static SecurityEvent event(String tenant, String host) {
        return new SecurityEvent("id-" + tenant + '-' + host, Instant.now(), "auth", host,
                "payload", Map.of("tenant_id", tenant), Severity.INFO);
    }
}
