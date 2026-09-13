package com.socp.detect.model.engine;

import com.socp.platform.tenant.context.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AlertWindowAggregatorTest {

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void windowsAreIsolatedByTenant() {
        AlertWindowAggregator aggregator = new AlertWindowAggregator();
        aggregator.record("tenant-a", "rule-a", "host-a", "HIGH");
        aggregator.record("tenant-a", "rule-a", "host-a", "HIGH");
        aggregator.record("tenant-b", "rule-b", "host-b", "LOW");

        assertEquals(2L, aggregator.snapshot("tenant-a").get("total"));
        assertEquals(1L, aggregator.snapshot("tenant-b").get("total"));

        TenantContext.set("tenant-c");
        assertEquals(0L, aggregator.snapshot().get("total"));
    }

    @Test
    void boundsTenantWindowCardinality() {
        AlertWindowAggregator aggregator = new AlertWindowAggregator();
        ReflectionTestUtils.setField(aggregator, "maxTenants", 1);
        aggregator.record("tenant-a", "rule-a", "host-a", "HIGH");
        aggregator.record("tenant-b", "rule-b", "host-b", "LOW");

        aggregator.tick();

        assertEquals(1, aggregator.cachedTenantWindows());
    }

    @Test
    void boundsHighCardinalityWindowDimensionsWithOverflowBucket() {
        AlertWindowAggregator aggregator = new AlertWindowAggregator();
        ReflectionTestUtils.setField(aggregator, "maxDimensions", 3);

        aggregator.record("tenant-a", "rule-a", "entity-a", "HIGH");
        aggregator.record("tenant-a", "rule-b", "entity-b", "LOW");
        aggregator.record("tenant-a", "rule-c", "entity-c", "MEDIUM");

        @SuppressWarnings("unchecked")
        var byRule = (java.util.Map<String, Long>) aggregator.snapshot("tenant-a").get("byRule");
        assertTrue(byRule.size() <= 3);
        assertTrue(byRule.containsKey("OTHER"));
    }
}
