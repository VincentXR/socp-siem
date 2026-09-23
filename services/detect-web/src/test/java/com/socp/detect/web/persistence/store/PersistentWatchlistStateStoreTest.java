package com.socp.detect.web.persistence.store;


import com.socp.detect.web.persistence.repository.WatchlistRepository;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DataJpaTest
@org.springframework.transaction.annotation.Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
class PersistentWatchlistStateStoreTest {

    @Autowired
    private WatchlistRepository repository;
    @Autowired private org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired private org.springframework.transaction.PlatformTransactionManager transactions;

    @org.junit.jupiter.api.BeforeEach
    void prepare() {
        jdbc.update("delete from t_watchlist");
        jdbc.update("delete from t_watchlist_namespace");
        com.socp.rule.engine.Watchlists.putTemplate("blocked_ips", Set.of("inherited"));
    }

    @org.junit.jupiter.api.AfterEach
    void clearFixture() {
        jdbc.update("delete from t_watchlist");
        jdbc.update("delete from t_watchlist_namespace");
    }

    @Test
    void savedValuesAndTombstonesAreSharedAcrossStoreInstances() {
        PersistentWatchlistStateStore first = new PersistentWatchlistStateStore(repository, new ObjectMapper(), jdbc, transactions);
        PersistentWatchlistStateStore second = new PersistentWatchlistStateStore(repository, new ObjectMapper(), jdbc, transactions);

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
        PersistentWatchlistStateStore store = new PersistentWatchlistStateStore(repository, new ObjectMapper(), jdbc, transactions);
        ReflectionTestUtils.setField(store, "refreshMs", 60_000L);
        ReflectionTestUtils.setField(store, "maxCacheEntries", 1);

        store.find("tenant-a", "first");
        store.find("tenant-b", "second");

        assertEquals(1, store.cachedEntries());
    }

    @Test
    void clearDeletesOnlyTheCurrentTenantAndInvalidatesCache() {
        WatchlistRepository mocked = mock(WatchlistRepository.class);
        PersistentWatchlistStateStore store = new PersistentWatchlistStateStore(mocked, new ObjectMapper(), jdbc, transactions);
        org.springframework.test.util.ReflectionTestUtils.setField(store, "refreshMs", 60_000L);
        com.socp.platform.tenant.context.TenantContext.set("tenant-a");

        when(mocked.findByTenantIdAndListName("tenant-a", "blocked_ips"))
                .thenReturn(java.util.Optional.empty());
        store.find("tenant-a", "blocked_ips");
        store.clear();

        verify(mocked).deleteByTenantId("tenant-a");
        assertEquals(0, store.cachedEntries());
        com.socp.platform.tenant.context.TenantContext.clear();
    }

    @Test
    void twoInstancesAppendFromDurableStateDespiteAStaleReadCache() throws Exception {
        var first = new PersistentWatchlistStateStore(repository, new ObjectMapper(), jdbc, transactions);
        var second = new PersistentWatchlistStateStore(repository, new ObjectMapper(), jdbc, transactions);
        ReflectionTestUtils.setField(first, "refreshMs", 60_000L);
        ReflectionTestUtils.setField(second, "refreshMs", 60_000L);
        first.save("tenant-a", "shared", Set.of("base"));
        first.find("tenant-a", "shared");
        second.find("tenant-a", "shared");
        var ready = new java.util.concurrent.CountDownLatch(2);
        var start = new java.util.concurrent.CountDownLatch(1);
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var one = executor.submit(() -> { ready.countDown(); start.await(); return append(first, "shared", "one"); });
            var two = executor.submit(() -> { ready.countDown(); start.await(); return append(second, "shared", "two"); });
            try { assertTrue(ready.await(5, java.util.concurrent.TimeUnit.SECONDS)); }
            finally { start.countDown(); }
            one.get(10, java.util.concurrent.TimeUnit.SECONDS);
            two.get(10, java.util.concurrent.TimeUnit.SECONDS);
        }
        var reader = new PersistentWatchlistStateStore(repository, new ObjectMapper(), jdbc, transactions);
        assertEquals(Set.of("base", "one", "two"), reader.find("tenant-a", "shared").values());
    }

    @Test
    void firstNamespaceCreationAndFirstListCreationCanRaceWithoutLosingValues() throws Exception {
        var first = new PersistentWatchlistStateStore(repository, new ObjectMapper(), jdbc, transactions);
        var second = new PersistentWatchlistStateStore(repository, new ObjectMapper(), jdbc, transactions);
        var start = new java.util.concurrent.CountDownLatch(1);
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var one = executor.submit(() -> { start.await(); return append(first, "new-list", "one"); });
            var two = executor.submit(() -> { start.await(); return append(second, "new-list", "two"); });
            start.countDown();
            one.get(10, java.util.concurrent.TimeUnit.SECONDS);
            two.get(10, java.util.concurrent.TimeUnit.SECONDS);
        }
        assertEquals(Set.of("one", "two"), first.find("tenant-a", "new-list").values());
    }

    @Test
    void rejectedMutationPreservesThePriorStateAndCache() {
        var store = new PersistentWatchlistStateStore(repository, new ObjectMapper(), jdbc, transactions);
        ReflectionTestUtils.setField(store, "refreshMs", 60_000L);
        store.save("tenant-a", "shared", Set.of("base"));
        store.find("tenant-a", "shared");
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> store.update("tenant-a", "shared", current -> {
                    throw new IllegalArgumentException("quota exceeded");
                }));
        assertEquals(Set.of("base"), store.find("tenant-a", "shared").values());
    }

    @Test
    void simultaneousCreatesHaveOneWinnerAndNeverReplaceTheWinner() throws Exception {
        var persistent = new PersistentWatchlistStateStore(repository, new ObjectMapper(), jdbc, transactions);
        var store = new WatchlistStore(persistent);
        store.init();
        var start = new java.util.concurrent.CountDownLatch(1);
        var ready = new java.util.concurrent.CountDownLatch(2);
        var successes = new java.util.concurrent.CopyOnWriteArrayList<java.util.Map<String, Object>>();
        var conflicts = new java.util.concurrent.atomic.AtomicInteger();
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = java.util.stream.Stream.of("alice", "bob").map(member -> executor.submit(() -> {
                ready.countDown();
                start.await();
                try (var ignored = com.socp.platform.tenant.context.TenantContext.open("tenant-a")) {
                    try { successes.add(store.create("Shared", java.util.List.of(member))); }
                    catch (com.socp.platform.error.exception.ApiException conflict) {
                        assertEquals(409, conflict.getCode());
                        conflicts.incrementAndGet();
                    }
                }
                return null;
            })).toList();
            try { assertTrue(ready.await(5, java.util.concurrent.TimeUnit.SECONDS)); }
            finally { start.countDown(); }
            for (var future : futures) future.get(10, java.util.concurrent.TimeUnit.SECONDS);
            assertEquals(1, successes.size());
            assertEquals(1, conflicts.get());
            assertEquals("shared", successes.getFirst().get("name"));
            assertEquals(successes.getFirst().get("values"),
                    persistent.findFresh("tenant-a", "shared").values());
        } finally {
            com.socp.platform.tenant.context.TenantContext.runWith("tenant-a", com.socp.rule.engine.Watchlists::clear);
        }
    }

    @Test
    void aWriteIsNotPublishedToTheCacheBeforeItsCommit() throws Exception {
        var store = new PersistentWatchlistStateStore(repository, new ObjectMapper(), jdbc, transactions);
        ReflectionTestUtils.setField(store, "refreshMs", 60_000L);
        store.save("tenant-a", "shared", Set.of("base"));
        store.find("tenant-a", "shared");
        var beforeCommit = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var writer = java.util.concurrent.CompletableFuture.runAsync(() -> store.update("tenant-a", "shared", current -> {
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                    new org.springframework.transaction.support.TransactionSynchronization() {
                        @Override public void beforeCommit(boolean readOnly) {
                            beforeCommit.countDown();
                            try {
                                if (!release.await(5, java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("commit release timeout");
                            } catch (InterruptedException failure) { throw new AssertionError(failure); }
                        }
                    });
            return new WatchlistStateStore.State(Set.of("committed"), false);
        }));
        try {
            assertTrue(beforeCommit.await(5, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(Set.of("base"), store.find("tenant-a", "shared").values());
        } finally { release.countDown(); }
        writer.get(5, java.util.concurrent.TimeUnit.SECONDS);
        assertEquals(Set.of("committed"), store.find("tenant-a", "shared").values());
    }

    @Test
    void cacheWeightBoundsLargeListsAsWellAsEntryCount() {
        var store = new PersistentWatchlistStateStore(repository, new ObjectMapper(), jdbc, transactions);
        ReflectionTestUtils.setField(store, "refreshMs", 60_000L);
        ReflectionTestUtils.setField(store, "maxCacheWeight", 1000L);
        Set<String> values = new java.util.HashSet<>();
        for (int i = 0; i < 20; i++) values.add("value-" + i);
        store.save("tenant-a", "large", values);
        assertEquals(values, store.find("tenant-a", "large").values());
        assertEquals(0, store.cachedEntries());
        assertTrue(store.cachedWeight() <= 1000);
    }

    @Test
    void compactProjectionTracksCommittedValuesAndTemplateTombstones() {
        var store = new PersistentWatchlistStateStore(repository, new ObjectMapper(), jdbc, transactions);
        store.save("tenant-a", "blocked_ips", Set.of("one", "two"));
        store.save("tenant-b", "other", Set.of("private"));
        var summaries = store.summaries("tenant-a");
        assertEquals(1, summaries.size());
        assertEquals("blocked_ips", summaries.getFirst().getName());
        assertEquals(2, summaries.getFirst().getSize());
        assertFalse(summaries.getFirst().getDeleted());
        store.delete("tenant-a", "blocked_ips");
        summaries = store.summaries("tenant-a");
        assertEquals(0, summaries.getFirst().getSize());
        assertTrue(summaries.getFirst().getDeleted());
    }

    @Test
    void concurrentCreationCannotExceedTheTenantQuotaAndDeletionReleasesASlot() throws Exception {
        for (int i = 0; i < com.socp.rule.engine.WatchlistLimits.MAX_LISTS - 1; i++) {
            jdbc.update("insert into t_watchlist (tenant_id, list_name, values_json, deleted, row_version, updated_at) "
                    + "values ('tenant-a', ?, '[]', false, 0, current_timestamp)", "existing-" + i);
        }
        var first = new PersistentWatchlistStateStore(repository, new ObjectMapper(), jdbc, transactions);
        var second = new PersistentWatchlistStateStore(repository, new ObjectMapper(), jdbc, transactions);
        var start = new java.util.concurrent.CountDownLatch(1);
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var one = executor.submit(() -> { start.await(); return createWithinQuota(first, "one"); });
            var two = executor.submit(() -> { start.await(); return createWithinQuota(second, "two"); });
            start.countDown();
            assertEquals(1, one.get(10, java.util.concurrent.TimeUnit.SECONDS) + two.get(10, java.util.concurrent.TimeUnit.SECONDS));
        }
        assertEquals(500, repository.countByTenantId("tenant-a"));
        first.delete("tenant-a", "existing-0");
        first.save("tenant-a", "replacement", Set.of("value"));
        assertEquals(500, repository.countByTenantId("tenant-a"));
        first.save("tenant-b", "independent", Set.of("value"));
        assertEquals(1, repository.countByTenantId("tenant-b"));
    }

    private int createWithinQuota(PersistentWatchlistStateStore store, String name) {
        try { store.save("tenant-a", name, Set.of("value")); return 1; }
        catch (com.socp.platform.error.exception.ApiException limit) {
            assertEquals(409, limit.getCode());
            return 0;
        }
    }

    private WatchlistStateStore.State append(PersistentWatchlistStateStore store, String name, String value) {
        return store.update("tenant-a", name, current -> {
            var values = new java.util.LinkedHashSet<String>(current == null ? Set.of() : current.values());
            values.add(value);
            return new WatchlistStateStore.State(values, false);
        });
    }
}
