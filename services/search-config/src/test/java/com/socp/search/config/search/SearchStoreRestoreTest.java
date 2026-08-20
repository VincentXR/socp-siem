package com.socp.search.config.search;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SearchStoreRestoreTest {

    @Test
    void restoresOnlyTheBoundedHotWindowInsteadOfTheFullTable() {
        SearchEventRepository repository = mock(SearchEventRepository.class);
        SearchEventEntity newest = SearchStore.toEntity(new SearchEvent(
                "event-new", Instant.parse("2026-08-20T00:00:00Z"), "auth", "host-1",
                "HIGH", "newest", Map.of(), Map.of()));
        SearchEventEntity older = SearchStore.toEntity(new SearchEvent(
                "event-old", Instant.parse("2026-08-19T00:00:00Z"), "auth", "host-1",
                "INFO", "older", Map.of(), Map.of()));
        when(repository.count()).thenReturn(1_000_000L);
        when(repository.findAll(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(newest, older)));

        SearchStore store = new SearchStore(repository, null);

        assertEquals(List.of("event-old", "event-new"),
                store.all().stream().map(SearchEvent::eventId).toList());
        assertEquals(1_000_000L, store.realCount());
        verify(repository).findAll(any(Pageable.class));
    }
}
