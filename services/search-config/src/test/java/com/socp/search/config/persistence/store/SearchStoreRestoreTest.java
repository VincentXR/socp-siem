package com.socp.search.config.persistence.store;

import com.socp.search.config.domain.SearchEvent;
import com.socp.search.config.persistence.entity.SearchEventEntity;
import com.socp.search.config.persistence.repository.SearchEventRepository;

import com.socp.platform.tenant.context.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
        when(repository.findTop20000ByTenantIdOrderByTimestampDesc("default"))
                .thenReturn(List.of(newest, older));

        SearchStore store = new SearchStore(repository, null);

        assertEquals(List.of("event-old", "event-new"),
                store.all().stream().map(SearchEvent::eventId).toList());
        assertEquals(1_000_000L, store.realCount());
        verify(repository).findTop20000ByTenantIdOrderByTimestampDesc("default");
    }

    @Test
    void hotWindowIsTenantScoped() {
        SearchEventRepository repository = mock(SearchEventRepository.class);
        when(repository.findTop20000ByTenantIdOrderByTimestampDesc("default")).thenReturn(List.of());
        when(repository.findTop20000ByTenantIdOrderByTimestampDesc("tenant-a")).thenReturn(List.of());
        when(repository.findTop20000ByTenantIdOrderByTimestampDesc("tenant-b")).thenReturn(List.of());
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
        when(repository.findTop20000ByTenantIdOrderByTimestampDesc("default")).thenReturn(List.of());
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
    void boundsTenantBufferCardinality() {
        SearchEventRepository repository = mock(SearchEventRepository.class);
        when(repository.findTop20000ByTenantIdOrderByTimestampDesc("default")).thenReturn(List.of());
        when(repository.findTop20000ByTenantIdOrderByTimestampDesc("tenant-a")).thenReturn(List.of());
        when(repository.findTop20000ByTenantIdOrderByTimestampDesc("tenant-b")).thenReturn(List.of());
        SearchStore store = new SearchStore(repository, null);
        ReflectionTestUtils.setField(store, "maxTenantBuffers", 1);

        TenantContext.set("tenant-a");
        store.all();
        TenantContext.set("tenant-b");
        store.all();
        store.evictIdleTenantBuffers();

        assertEquals(1, store.cachedTenantBuffers());
    }
}
