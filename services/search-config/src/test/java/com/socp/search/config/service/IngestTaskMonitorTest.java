package com.socp.search.config.service;

import com.socp.platform.tenant.context.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
