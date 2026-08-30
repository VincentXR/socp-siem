package com.socp.detect.web.persistence.store;

import com.socp.platform.tenant.context.TenantContext;
import com.socp.rule.engine.WatchlistStateStore;
import com.socp.rule.engine.Watchlists;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WatchlistStoreTest {

    private PersistentWatchlistStateStore persistent;
    private WatchlistStore store;

    @BeforeEach
    void setUp() {
        Watchlists.clear();
        TenantContext.set("tenant-watchlist");
        persistent = mock(PersistentWatchlistStateStore.class);
        store = new WatchlistStore(persistent);
        store.init();
    }

    @AfterEach
    void tearDown() {
        Watchlists.clear();
        TenantContext.clear();
    }

    @Test
    void seedsTemplatesAndDelegatesTenantMutationsToDurableState() {
        when(persistent.names("tenant-watchlist")).thenReturn(Set.of());
        assertThat(store.list()).extracting(item -> item.get("name"))
                .contains("privileged_accounts", "blocked_ips", "crown_jewels");

        when(persistent.find("tenant-watchlist", "blocked_ips"))
                .thenReturn(new WatchlistStateStore.State(Set.of("203.0.113.66"), false));
        when(persistent.names("tenant-watchlist")).thenReturn(Set.of("blocked_ips"));

        Map<String, Object> replaced = store.put("blocked_ips", List.of("203.0.113.66"));
        Map<String, Object> appended = store.append("blocked_ips", List.of("198.51.100.23"));

        assertThat(replaced).containsEntry("name", "blocked_ips").containsEntry("size", 1);
        assertThat(appended).containsEntry("size", 1);
        verify(persistent).save(eq("tenant-watchlist"), eq("blocked_ips"), eq(Set.of("203.0.113.66")));
        verify(persistent).save(eq("tenant-watchlist"), eq("blocked_ips"),
                eq(Set.of("203.0.113.66", "198.51.100.23")));
    }

    @Test
    void describesAndDeletesTenantScopedEntries() {
        when(persistent.names("tenant-watchlist")).thenReturn(Set.of("custom"));
        when(persistent.find("tenant-watchlist", "custom"))
                .thenReturn(new WatchlistStateStore.State(Set.of("admin", "root"), false));

        assertThat(store.describe("custom"))
                .containsEntry("name", "custom")
                .containsEntry("size", 2)
                .extractingByKey("values")
                .isEqualTo(Set.of("admin", "root"));
        assertThat(store.delete("custom")).isTrue();
        verify(persistent).delete("tenant-watchlist", "custom");
    }
}
