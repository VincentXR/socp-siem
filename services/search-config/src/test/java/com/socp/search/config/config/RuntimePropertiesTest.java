package com.socp.search.config.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimePropertiesTest {

    @Test
    void ingestPropertiesExposeNestedRuntimeBounds() {
        IngestRuntimeProperties properties = new IngestRuntimeProperties();
        assertFalse(properties.isForwardHttp());
        assertEquals(10_000, properties.getMonitor().getMaxEntries());
        assertEquals(12, properties.getOutbox().getMaxAttempts());
        assertEquals(1_000, properties.getOutbox().getCleanupBatchSize());
        assertEquals(10, properties.getOutbox().getCleanupMaxBatches());

        properties.setForwardHttp(true);
        properties.getMonitor().setMaxEntries(20);
        properties.getOutbox().setDeliveryConcurrency(4);

        assertTrue(properties.isForwardHttp());
        assertEquals(20, properties.getMonitor().getMaxEntries());
        assertEquals(4, properties.getOutbox().getDeliveryConcurrency());
    }

    @Test
    void searchAndCollectorPropertiesHaveExplicitDefaults() {
        SearchCacheProperties cache = new SearchCacheProperties();
        OpenSearchIndexerProperties indexer = new OpenSearchIndexerProperties();
        VectorProperties vector = new VectorProperties();

        assertEquals(1_800_000L, cache.getIdleTtlMs());
        assertEquals(100, cache.getMaxTenants());
        assertTrue(indexer.isEnabled());
        assertEquals(1_000L, indexer.getRetryBackoffMs());
        assertEquals("dev-vector-token", vector.getToken());

        cache.setMaxTenants(8);
        indexer.setEnabled(false);
        vector.setToken("collector-token");

        assertEquals(8, cache.getMaxTenants());
        assertFalse(indexer.isEnabled());
        assertEquals("collector-token", vector.getToken());
    }
}
