package com.socp.detect.web.persistence.store;



import com.socp.detect.web.persistence.store.*;
import com.socp.detect.web.persistence.repository.*;
import com.socp.detect.web.persistence.entity.*;
import com.socp.platform.tenant.context.TenantContext;
import com.socp.rule.model.SecurityEvent;
import com.socp.rule.model.Severity;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class InMemoryDetectionStateStoreTest {

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
}
