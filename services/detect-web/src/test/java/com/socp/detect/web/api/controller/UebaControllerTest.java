package com.socp.detect.web.api.controller;

import com.socp.detect.web.persistence.store.WatchlistStore;
import com.socp.detect.web.service.EntityRiskStore;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UebaControllerTest {

    @Test
    void exposesRiskQueriesAndScoreExplanation() {
        EntityRiskStore riskStore = mock(EntityRiskStore.class);
        WatchlistStore watchlists = mock(WatchlistStore.class);
        List<Map<String, Object>> entities = List.of(Map.of("entity", "admin", "risk", 80.0));
        Map<String, Object> entity = Map.of("entity", "admin", "level", "HIGH");
        Map<String, Object> summary = Map.of("entities", 1);
        when(riskStore.top(10)).thenReturn(entities);
        when(riskStore.get("admin")).thenReturn(entity);
        when(riskStore.summary()).thenReturn(summary);

        UebaController controller = new UebaController(riskStore, watchlists);

        assertThat(controller.entities(10)).isSameAs(entities);
        assertThat(controller.entity("admin").getStatusCode().value()).isEqualTo(200);
        assertThat(controller.entity("admin").getBody()).isEqualTo(entity);
        assertThat(controller.summary()).isSameAs(summary);
        assertThat(controller.score("HIGH", "T1110", 2, 3, 1))
                .containsKeys("score", "level", "breakdown")
                .extractingByKey("level").isEqualTo("HIGH");
        assertThat(controller.score("not-a-severity", null, 0, 0, 0))
                .extractingByKey("level").isEqualTo("INFO");

        verify(riskStore).top(eq(10));
        verify(riskStore, times(2)).get(eq("admin"));
        verify(riskStore).summary();
    }

    @Test
    void returnsNotFoundAndManagesWatchlists() {
        EntityRiskStore riskStore = mock(EntityRiskStore.class);
        WatchlistStore watchlists = mock(WatchlistStore.class);
        when(riskStore.get("missing")).thenReturn(null);
        List<Map<String, Object>> listed = List.of(Map.of("name", "blocked_ips"));
        Map<String, Object> described = Map.of("name", "blocked_ips", "size", 1);
        Map<String, Object> replaced = Map.of("name", "blocked_ips", "size", 2);
        when(watchlists.list()).thenReturn(listed);
        when(watchlists.describe("blocked_ips")).thenReturn(described);
        when(watchlists.put("blocked_ips", List.of("203.0.113.66"))).thenReturn(replaced);
        when(watchlists.append("blocked_ips", List.of("198.51.100.23"))).thenReturn(replaced);
        when(watchlists.delete("blocked_ips")).thenReturn(true);

        UebaController controller = new UebaController(riskStore, watchlists);

        assertThat(controller.entity("missing").getStatusCode().value()).isEqualTo(404);
        assertThat(controller.listWatchlists()).isSameAs(listed);
        assertThat(controller.getWatchlist("blocked_ips")).isSameAs(described);
        assertThat(controller.putWatchlist("blocked_ips", List.of("203.0.113.66")))
                .isSameAs(replaced);
        assertThat(controller.appendWatchlist("blocked_ips", List.of("198.51.100.23")))
                .isSameAs(replaced);
        assertThat(controller.deleteWatchlist("blocked_ips")).containsEntry("removed", true);

        verify(watchlists).list();
        verify(watchlists).describe("blocked_ips");
        verify(watchlists).delete("blocked_ips");
    }
}
