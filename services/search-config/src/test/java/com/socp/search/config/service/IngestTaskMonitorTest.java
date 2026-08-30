package com.socp.search.config.service;

import com.socp.platform.tenant.context.TenantContext;
import com.socp.search.config.config.IngestRuntimeProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IngestTaskMonitorTest {

    @BeforeEach
    void setTenant() {
        TenantContext.set("default");
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void boundsCollectorStatistics() {
        IngestTaskMonitor monitor = new IngestTaskMonitor();
        ReflectionTestUtils.setField(monitor, "maxEntries", 1);
        monitor.record("collector-a", 1, 0, 1, 100);
        monitor.record("collector-b", 1, 0, 1, 100);

        monitor.cleanupIdleStats();

        assertEquals(1, monitor.cachedStats());
    }

    @Test
    void reportsIdleDisabledHealthyDegradedAndErrorStates() {
        IngestTaskMonitor monitor = new IngestTaskMonitor();

        assertEquals("IDLE", monitor.runtime("missing", true).get("health"));
        assertEquals("DISABLED", monitor.runtime("missing", false).get("health"));

        monitor.record("healthy", 10, 1, 9, 1000);
        assertEquals("HEALTHY", monitor.runtime("healthy", true).get("health"));

        monitor.record("degraded", 1, 4, 1, 100);
        assertEquals("DEGRADED", monitor.runtime("degraded", true).get("health"));

        monitor.recordError("healthy", "broker unavailable");
        Map<String, Object> error = monitor.runtime("healthy", true);
        assertEquals("ERROR", error.get("health"));
        assertEquals("broker unavailable", error.get("lastError"));
        assertTrue(((Number) error.get("accepted")).longValue() > 0);
    }

    @Test
    void computesRatesNormalizesCollectorsAndKeepsTenantsIsolated() {
        IngestTaskMonitor monitor = new IngestTaskMonitor();
        monitor.record("  AUTH ", 5, 0, 4, 500);
        monitor.record(null, 2, 1, 1, 200);

        Map<String, Object> auth = monitor.runtime("auth", true);
        assertEquals(5L, auth.get("accepted"));
        assertEquals(500L, auth.get("bytes"));
        assertTrue(((Number) auth.get("eps1m")).doubleValue() > 0);
        assertEquals(2L, monitor.runtime("unknown", true).get("accepted"));

        assertEquals(2, monitor.summary(List.of("auth")).get("collectors"));
        assertEquals(7L, monitor.summary(List.of("auth")).get("accepted"));

        TenantContext.set("other-tenant");
        monitor.record("auth", 99, 0, 99, 9900);
        assertEquals(1, monitor.summary(null).get("collectors"));
        assertEquals(99L, monitor.summary(null).get("accepted"));
    }

    @Test
    void marksStaleCollectorsAndRemovesExpiredEntries() {
        IngestRuntimeProperties properties = new IngestRuntimeProperties();
        properties.getMonitor().setIdleTtlMs(1);
        IngestTaskMonitor monitor = new IngestTaskMonitor(properties);
        monitor.record("stale", 1, 0, 1, 1);

        Object stat = ((Map<?, ?>) ReflectionTestUtils.getField(monitor, "stats"))
                .get("default|stale");
        ReflectionTestUtils.setField(stat, "lastAt", Instant.now().minusSeconds(301));
        ReflectionTestUtils.setField(stat, "lastTouchedMillis", System.currentTimeMillis() - 120_000);
        assertEquals("STALE", monitor.runtime("stale", true).get("health"));

        monitor.cleanupIdleStats();
        assertEquals(0, monitor.cachedStats());
    }
}
