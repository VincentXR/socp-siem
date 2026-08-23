package com.socp.detect.web.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.socp.rule.engine.WatchlistStateStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DataJpaTest
class PersistentWatchlistStateStoreTest {

    @Autowired
    private WatchlistRepository repository;

    @Test
    void savedValuesAndTombstonesAreSharedAcrossStoreInstances() {
        PersistentWatchlistStateStore first = new PersistentWatchlistStateStore(repository, new ObjectMapper());
        PersistentWatchlistStateStore second = new PersistentWatchlistStateStore(repository, new ObjectMapper());

        first.save("tenant-a", "blocked_ips", Set.of("203.0.113.66"));

        WatchlistStateStore.State saved = second.find("tenant-a", "blocked_ips");
        assertNotNull(saved);
        assertFalse(saved.deleted());
        assertEquals(Set.of("203.0.113.66"), saved.values());

        first.delete("tenant-a", "blocked_ips");

        WatchlistStateStore.State deleted = second.find("tenant-a", "blocked_ips");
        assertNotNull(deleted);
        assertTrue(deleted.deleted());
        assertTrue(second.names("tenant-a").contains("blocked_ips"));
    }

    @Test
    void boundsCachedWatchlistEntries() {
        PersistentWatchlistStateStore store = new PersistentWatchlistStateStore(repository, new ObjectMapper());
        ReflectionTestUtils.setField(store, "refreshMs", 60_000L);
        ReflectionTestUtils.setField(store, "maxCacheEntries", 1);

        store.find("tenant-a", "first");
        store.find("tenant-b", "second");

        assertEquals(1, store.cachedEntries());
    }
}
