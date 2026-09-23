package com.socp.search.config.persistence.store;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TenantRevisionTrackerTest {

    @Test
    void boundedTenantTokensStillInvalidateAfterEvictionAndRecreation() {
        TenantRevisionTracker tracker = new TenantRevisionTracker();
        tracker.bump("first");
        long firstToken = tracker.revision("first");
        for (int index = 0; index < 4096; index++) {
            tracker.bump("tenant-" + index);
            assertTrue(tracker.trackedTenants() <= 4096);
        }
        assertEquals(0L, tracker.revision("first"));
        long lastToken = tracker.revision("tenant-4095");
        tracker.bump("first");
        assertTrue(tracker.revision("first") > firstToken);
        assertTrue(tracker.revision("first") > lastToken);
    }
}
