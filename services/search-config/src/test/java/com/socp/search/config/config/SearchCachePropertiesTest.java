package com.socp.search.config.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SearchCachePropertiesTest {

    @Test
    void exposesConfiguredWarmupAndBudgetBounds() {
        SearchCacheProperties properties = new SearchCacheProperties();
        properties.setIdleTtlMs(1234L);
        properties.setMaxTenants(4);
        properties.setWarmupMaxEvents(256);
        properties.setWarmupBatchSize(32);
        properties.setMaxConcurrentWarmups(3);
        properties.setMaxBytesPerTenant(4096L);
        properties.setMaxBytesTotal(16384L);

        properties.validate();

        assertEquals(1234L, properties.getIdleTtlMs());
        assertEquals(4, properties.getMaxTenants());
        assertEquals(256, properties.getWarmupMaxEvents());
        assertEquals(32, properties.getWarmupBatchSize());
        assertEquals(3, properties.getMaxConcurrentWarmups());
        assertEquals(4096L, properties.getMaxBytesPerTenant());
        assertEquals(16384L, properties.getMaxBytesTotal());
    }

    @Test
    void rejectsInvalidWarmupBatchSize() {
        SearchCacheProperties properties = new SearchCacheProperties();
        properties.setWarmupBatchSize(0);

        assertThrows(IllegalArgumentException.class, properties::validate);
    }

    @Test
    void rejectsInvalidConcurrentWarmupCount() {
        SearchCacheProperties properties = new SearchCacheProperties();
        properties.setMaxConcurrentWarmups(0);

        assertThrows(IllegalArgumentException.class, properties::validate);
    }

    @Test
    void rejectsInvalidTotalBudget() {
        SearchCacheProperties properties = new SearchCacheProperties();
        properties.setMaxBytesTotal(0L);

        assertThrows(IllegalArgumentException.class, properties::validate);
    }

    @Test
    void rejectsTotalBudgetBelowPerTenantBudget() {
        SearchCacheProperties properties = new SearchCacheProperties();
        properties.setMaxBytesPerTenant(4096L);
        properties.setMaxBytesTotal(2048L);

        assertThrows(IllegalArgumentException.class, properties::validate);
    }
}
