package com.socp.search.config.api.controller;





import com.socp.search.config.persistence.store.*;
import com.socp.search.config.parser.*;
import com.socp.search.config.domain.*;
import com.socp.search.config.domain.*;
import com.socp.search.config.infrastructure.kafka.*;
import com.socp.search.config.infrastructure.opensearch.*;
import com.socp.search.config.infrastructure.serialization.*;
import com.socp.search.config.persistence.entity.*;
import com.socp.search.config.persistence.repository.*;
import com.socp.search.config.persistence.store.*;
import com.socp.search.config.service.*;
import com.socp.search.config.api.request.*;
import com.socp.platform.error.exception.ApiException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

class SearchControllerTest {

    @Test
    void returnsOpenSearchAsTheAuthoritativeSourceWithoutMergingCaches() {
        SplEngine engine = mock(SplEngine.class);
        SearchStore store = mock(SearchStore.class);
        OsEventReader reader = mock(OsEventReader.class);
        SearchEvent event = event("os-1", "2026-08-23T10:00:00Z");
        given(reader.search("source=auth", 200)).willReturn(new SplEngine.QueryResult(1, List.of(event), null));

        SplEngine.QueryResult result = new SearchController(engine, store, reader).search("source=auth");

        assertThat(result.source()).isEqualTo("opensearch");
        assertThat(result.degraded()).isFalse();
        assertThat(result.freshness()).isEqualTo(event.timestamp());
        assertThat(result.events()).containsExactly(event);
    }

    @Test
    void marksTheLocalCacheFallbackAsDegraded() {
        SplEngine engine = new SplEngine();
        SearchStore store = mock(SearchStore.class);
        OsEventReader reader = mock(OsEventReader.class);
        SearchEvent event = event("local-1", "2026-08-23T11:00:00Z");
        given(reader.search("source=auth", 200)).willReturn(null);
        given(store.all()).willReturn(List.of(event));

        SplEngine.QueryResult result = new SearchController(engine, store, reader).search("source=auth");

        assertThat(result.source()).isEqualTo("local-cache");
        assertThat(result.degraded()).isTrue();
        assertThat(result.degradationReason()).contains("OpenSearch");
        assertThat(result.total()).isEqualTo(1);
    }

    @Test
    void failsWith503WhenBothSearchSourcesAreUnavailable() {
        SplEngine engine = mock(SplEngine.class);
        SearchStore store = mock(SearchStore.class);
        OsEventReader reader = mock(OsEventReader.class);
        given(reader.search("q", 200)).willReturn(null);
        given(store.all()).willThrow(new IllegalStateException("H2 unavailable"));

        assertThatThrownBy(() -> new SearchController(engine, store, reader).search("q"))
                .isInstanceOf(ApiException.class)
                .extracting(error -> ((ApiException) error).getCode())
                .isEqualTo(503);
    }

    private static SearchEvent event(String id, String timestamp) {
        return new SearchEvent(id, Instant.parse(timestamp), "auth", "host-1", "HIGH", "failed login",
                Map.of("tenant_id", "default"), Map.of());
    }
}
