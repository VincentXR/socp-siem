package com.socp.platform.ratelimit.store;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemoryRateLimitStoreTest {

    @Test
    void permitsAndRejectsWithinTheConfiguredBucketWindow() {
        InMemoryRateLimitStore store = new InMemoryRateLimitStore(10);

        assertTrue(store.acquire("tenant-a|login", 1, 60).allowed());
        assertFalse(store.acquire("tenant-a|login", 1, 60).allowed());
        for (int i = 0; i < 1_022; i++) {
            store.acquire("tenant-a|key-" + i, 1, 60);
        }
        assertTrue(store.size() <= 10);
    }
}
