package com.socp.search.config.service;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;

class IngestTaskMonitorTest {

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
