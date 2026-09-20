package com.socp.detect.web.persistence.store;


import com.socp.platform.tenant.context.TenantContext;
import com.socp.rule.model.SecurityEvent;
import com.socp.rule.model.Severity;
import com.socp.rule.partition.DetectionDelivery;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class InMemoryDetectionStateStoreTest {

    @Test
    void sameSourceEventHasIndependentDeliveryClaims() {
        TenantContext.set("tenant-a");
        try {
            InMemoryDetectionStateStore store = new InMemoryDetectionStateStore();
            SecurityEvent user = routed("source-1", "delivery-user", "user", "alice");
            SecurityEvent host = routed("source-1", "delivery-host", "host", "host-a");

            assertEquals(DetectionEventClaim.NEW, store.claim(user, 0, 1L, "tenant-a|user|alice"));
            assertEquals(DetectionEventClaim.NEW, store.claim(host, 1, 2L, "tenant-a|host|host-a"),
                    "a second route copy of the same source evidence must not be false-deduplicated");
            assertEquals(DetectionEventClaim.PENDING, store.claim(user, 0, 1L, "tenant-a|user|alice"));

            store.markCompleted(user);
            assertEquals(DetectionEventClaim.COMPLETED,
                    store.claim(user, 0, 1L, "tenant-a|user|alice"));
            assertEquals(DetectionEventClaim.PENDING,
                    store.claim(host, 1, 2L, "tenant-a|host|host-a"),
                    "completing one delivery must not complete its sibling route");
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    void claimLifecycleDistinguishesReplayableAndTerminalRows() {
        TenantContext.set("default");
        try {
            InMemoryDetectionStateStore store = new InMemoryDetectionStateStore();
            SecurityEvent event = new SecurityEvent("lifecycle-1", Instant.now(), "auth", "host-1",
                    "failed", Map.of("tenant_id", "default", "src_ip", "198.51.100.10"), Severity.HIGH);

            assertEquals(DetectionEventClaim.NEW, store.claim(event, 2, 11L, "tenant|src_ip|198.51.100.10"));
            assertEquals(DetectionEventClaim.PENDING, store.claim(event, 2, 11L, "tenant|src_ip|198.51.100.10"));
            assertEquals(1, store.pendingCount());

            store.markCompleted(event.id());
            assertEquals(DetectionEventClaim.COMPLETED, store.claim(event, 2, 11L, "tenant|src_ip|198.51.100.10"));
            assertEquals(0, store.pendingCount());
        } finally {
            TenantContext.clear();
        }
    }

    private static SecurityEvent routed(String sourceEventId, String deliveryId,
                                        String dimension, String value) {
        return new SecurityEvent(sourceEventId, Instant.now(), "auth", "host-a", "raw",
                Map.of("tenant_id", "tenant-a",
                        DetectionDelivery.DELIVERY_ID_FIELD, deliveryId,
                        DetectionDelivery.SOURCE_EVENT_ID_FIELD, sourceEventId,
                        DetectionDelivery.KIND_FIELD, DetectionDelivery.Kind.STATEFUL.name(),
                        DetectionDelivery.DIMENSION_FIELD, dimension,
                        DetectionDelivery.VALUE_FIELD, value,
                        DetectionDelivery.ROUTING_VERSION_FIELD, DetectionDelivery.ROUTING_VERSION),
                Severity.INFO);
    }
}
