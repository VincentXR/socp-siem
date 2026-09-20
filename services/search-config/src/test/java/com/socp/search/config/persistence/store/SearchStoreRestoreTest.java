package com.socp.search.config.persistence.store;

import com.socp.search.config.domain.SearchEvent;
import com.socp.search.config.persistence.entity.SearchEventEntity;
import com.socp.search.config.persistence.repository.SearchEventRepository;

import com.socp.platform.tenant.context.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.data.domain.Pageable;
import org.mockito.ArgumentCaptor;
import com.socp.search.config.config.SearchCacheProperties;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SearchStoreRestoreTest {

    @BeforeEach
    void setTenant() {
        TenantContext.set("default");
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void restoresOnlyTheBoundedHotWindowInsteadOfTheFullTable() {
        SearchEventRepository repository = mock(SearchEventRepository.class);
        SearchEventEntity newest = SearchStore.toEntity(new SearchEvent(
                "event-new", Instant.parse("2026-08-20T00:00:00Z"), "auth", "host-1",
                "HIGH", "newest", Map.of(), Map.of()));
        SearchEventEntity older = SearchStore.toEntity(new SearchEvent(
                "event-old", Instant.parse("2026-08-19T00:00:00Z"), "auth", "host-1",
                "INFO", "older", Map.of(), Map.of()));
        when(repository.countByTenantId("default")).thenReturn(1_000_000L);
        when(repository.findByTenantIdOrderByTimestampDesc(eq("default"), any(Pageable.class)))
                .thenReturn(List.of(newest, older));

        SearchStore store = new SearchStore(repository, null);

        assertEquals(List.of("event-old", "event-new"),
                store.all().stream().map(SearchEvent::eventId).toList());
        assertEquals(1_000_000L, store.realCount());
        ArgumentCaptor<Pageable> page = ArgumentCaptor.forClass(Pageable.class);
        verify(repository).findByTenantIdOrderByTimestampDesc(eq("default"), page.capture());
        assertEquals(128, page.getValue().getPageSize());
    }

    @Test
    void hotWindowIsTenantScoped() {
        SearchEventRepository repository = mock(SearchEventRepository.class);
        when(repository.findByTenantIdOrderByTimestampDesc(eq("default"), any(Pageable.class))).thenReturn(List.of());
        when(repository.findByTenantIdOrderByTimestampDesc(eq("tenant-a"), any(Pageable.class))).thenReturn(List.of());
        when(repository.findByTenantIdOrderByTimestampDesc(eq("tenant-b"), any(Pageable.class))).thenReturn(List.of());
        SearchStore store = new SearchStore(repository, null);

        store.rememberBatch(List.of(event("a-event", "tenant-a"), event("b-event", "tenant-b")));
        TenantContext.set("tenant-a");
        assertEquals(List.of("a-event"), store.all().stream().map(SearchEvent::eventId).toList());
        TenantContext.set("tenant-b");
        assertEquals(List.of("b-event"), store.all().stream().map(SearchEvent::eventId).toList());
    }

    private static SearchEvent event(String id, String tenant) {
        return new SearchEvent(id, Instant.EPOCH, "auth", "host", "INFO", "event",
                Map.of("tenant_id", tenant), Map.of());
    }

    @Test
    void retainsTheNewestTwentyThousandEventsWithoutArrayHeadCopies() {
        SearchEventRepository repository = mock(SearchEventRepository.class);
        when(repository.countByTenantId("default")).thenReturn(1L);
        when(repository.findByTenantIdOrderByTimestampDesc(eq("default"), any(Pageable.class))).thenReturn(List.of());
        SearchStore store = new SearchStore(repository, null);
        List<SearchEvent> events = IntStream.range(0, 20_100)
                .mapToObj(i -> new SearchEvent("event-" + i, Instant.EPOCH.plusSeconds(i),
                        "auth", "host-1", "INFO", "event", Map.of(), Map.of()))
                .toList();

        store.rememberBatch(events);

        assertEquals(20_000, store.size());
        assertEquals("event-100", store.all().getFirst().eventId());
        assertEquals("event-20099", store.all().getLast().eventId());
    }

    @Test
    void boundsCacheByEstimatedBytesAndEvictsOlderTenantBuffersGlobally() {
        SearchEventRepository repository = mock(SearchEventRepository.class);
        when(repository.findByTenantIdOrderByTimestampDesc(any(), any(Pageable.class)))
                .thenReturn(List.of());

        SearchCacheProperties properties = new SearchCacheProperties();
        properties.setMaxBytesPerTenant(4_096);
        properties.setMaxBytesTotal(6_000);
        properties.setMaxTenants(10);
        properties.setWarmupMaxEvents(10);
        SearchStore store = new SearchStore(repository, properties, false);

        TenantContext.set("tenant-a");
        store.rememberBatch(IntStream.range(0, 5)
                .mapToObj(i -> new SearchEvent("a-" + i, Instant.EPOCH.plusSeconds(i),
                        "auth", "host", "INFO", "x".repeat(1_500), Map.of(), Map.of()))
                .toList());
        assertTrue(store.cachedBytes() <= 4_096);
        assertTrue(store.size() < 5);

        TenantContext.set("tenant-b");
        store.rememberBatch(IntStream.range(0, 5)
                .mapToObj(i -> new SearchEvent("b-" + i, Instant.EPOCH.plusSeconds(i),
                        "auth", "host", "INFO", "y".repeat(1_500), Map.of(), Map.of()))
                .toList());

        assertTrue(store.cachedBytes() <= 6_000);
        assertEquals(1, store.cachedTenantBuffers(),
                "global byte budget should evict the older tenant buffer");
    }

    @Test
    void boundsTenantBufferCardinalityAtAdmission() {
        SearchEventRepository repository = mock(SearchEventRepository.class);
        when(repository.findByTenantIdOrderByTimestampDesc(any(), any(Pageable.class)))
                .thenReturn(List.of());
        SearchCacheProperties properties = new SearchCacheProperties();
        properties.setMaxTenants(1);
        SearchStore store = new SearchStore(repository, properties, false);

        TenantContext.set("tenant-a");
        store.all();
        TenantContext.set("tenant-b");
        store.all();

        assertEquals(1, store.cachedTenantBuffers(),
                "tenant cardinality must be enforced before scheduled cleanup");
    }

    @Test
    void oversizedEventIsPersistedButSkippedFromHotCache() {
        SearchEventRepository repository = mock(SearchEventRepository.class);
        when(repository.findByTenantIdOrderByTimestampDesc(any(), any(Pageable.class)))
                .thenReturn(List.of());
        SearchCacheProperties properties = new SearchCacheProperties();
        properties.setMaxBytesPerTenant(1_024);
        properties.setMaxBytesTotal(2_048);
        SearchStore store = new SearchStore(repository, properties, false);
        SearchEvent oversized = new SearchEvent("oversized-1", Instant.EPOCH, "auth", "host",
                "INFO", "x".repeat(8_192), Map.of(), Map.of());

        store.ingest(oversized);

        verify(repository).save(any(SearchEventEntity.class));
        assertEquals(0, store.size());
        assertEquals(0L, store.cachedBytes());
    }

    @Test
    void rejectsContradictoryTotalAndPerTenantBudgets() {
        SearchEventRepository repository = mock(SearchEventRepository.class);
        SearchCacheProperties properties = new SearchCacheProperties();
        properties.setMaxBytesPerTenant(4_096);
        properties.setMaxBytesTotal(1_024);

        assertThrows(IllegalArgumentException.class,
                () -> new SearchStore(repository, properties, false));
    }

    @Test
    void concurrentTenantWarmupsRespectConfiguredConcurrency() throws Exception {
        SearchEventRepository repository = mock(SearchEventRepository.class);
        AtomicInteger active = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        when(repository.findByTenantIdOrderByTimestampDesc(any(), any(Pageable.class)))
                .thenAnswer(invocation -> {
                    int current = active.incrementAndGet();
                    peak.accumulateAndGet(current, Math::max);
                    try {
                        Thread.sleep(100L);
                        return List.of();
                    } finally {
                        active.decrementAndGet();
                    }
                });

        SearchCacheProperties properties = new SearchCacheProperties();
        properties.setMaxTenants(2);
        properties.setWarmupMaxEvents(1);
        properties.setWarmupBatchSize(1);
        properties.setMaxConcurrentWarmups(1);
        SearchStore store = new SearchStore(repository, properties, false);

        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> {
                start.await();
                TenantContext.runWith("tenant-a", store::all);
                return null;
            });
            var second = executor.submit(() -> {
                start.await();
                TenantContext.runWith("tenant-b", store::all);
                return null;
            });
            start.countDown();
            first.get(5, TimeUnit.SECONDS);
            second.get(5, TimeUnit.SECONDS);
        }

        assertEquals(1, peak.get());
        assertEquals(2, store.cachedTenantBuffers());
    }

    @Test
    void evictedTenantReloadsFromPersistenceAndReplacementDoesNotDoubleCount() {
        SearchEventRepository repository = mock(SearchEventRepository.class);
        SearchEvent persisted = new SearchEvent("replace-1", Instant.EPOCH, "auth", "host",
                "INFO", "persisted", Map.of(), Map.of());
        SearchEventEntity persistedEntity = SearchStore.toEntity(persisted);
        AtomicInteger defaultWarmups = new AtomicInteger();
        when(repository.findByTenantIdOrderByTimestampDesc(any(), any(Pageable.class)))
                .thenAnswer(invocation -> {
                    String tenant = invocation.getArgument(0);
                    if (!"default".equals(tenant)) return List.of();
                    return defaultWarmups.incrementAndGet() == 1
                            ? List.of() : List.of(persistedEntity);
                });

        SearchCacheProperties properties = new SearchCacheProperties();
        properties.setMaxTenants(1);
        SearchStore store = new SearchStore(repository, properties, false);

        SearchEvent replacement = new SearchEvent("replace-1", Instant.EPOCH.plusSeconds(1),
                "auth", "host", "INFO", "replacement", Map.of(), Map.of());
        store.rememberBatch(List.of(persisted, replacement));
        assertEquals(1, store.size());
        assertEquals("replacement", store.all().getFirst().msg());

        TenantContext.set("tenant-b");
        store.all();
        assertEquals(1, store.cachedTenantBuffers());

        TenantContext.set("default");
        assertEquals("persisted", store.all().getFirst().msg());
        assertEquals(1, store.cachedTenantBuffers());
    }
}
