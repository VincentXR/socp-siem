package com.socp.threat.web.store;

import com.socp.platform.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class IocStoreTest {

    @Mock
    private IocRepository repository;

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void normalizesMatchAndCachesThePersistedTenantScopedResult() {
        TenantContext.set("tenant-a");
        IocEntity entity = entity("ioc-1", "DOMAIN", "example.com");
        when(repository.findByTenantIdAndValue("tenant-a", "example.com")).thenReturn(Optional.of(entity));
        IocStore store = new IocStore(repository, false);

        assertThat(store.match(" Example.COM ").value()).isEqualTo("example.com");
        assertThat(store.match("example.com").value()).isEqualTo("example.com");
        verify(repository, times(1)).findByTenantIdAndValue("tenant-a", "example.com");
    }

    @Test
    void batchMatchUsesInQueryAndKeepsOriginalInputKeys() {
        TenantContext.set("tenant-b");
        when(repository.findByTenantIdAndValueIn(eq("tenant-b"), anyList()))
                .thenReturn(List.of(entity("ioc-2", "IP", "203.0.113.7")));
        IocStore store = new IocStore(repository, false);

        var matched = store.matchAll(java.util.Arrays.asList("203.0.113.7", " missing ", " ", null));

        assertThat(matched).containsKey("203.0.113.7");
        assertThat(matched).doesNotContainKey("missing");
        verify(repository).findByTenantIdAndValueIn(eq("tenant-b"), eq(List.of("203.0.113.7", "missing")));
    }

    @Test
    void deleteIsTenantScopedAndRemovesCache() {
        TenantContext.set("tenant-c");
        IocEntity entity = entity("ioc-3", "IP", "203.0.113.8");
        when(repository.findByIdAndTenantId("ioc-3", "tenant-c")).thenReturn(Optional.of(entity));
        IocStore store = new IocStore(repository, false);

        assertThat(store.delete("ioc-3")).isTrue();
        verify(repository).delete(entity);
        when(repository.findByIdAndTenantId("ioc-4", "tenant-c")).thenReturn(Optional.empty());
        assertThat(store.delete("ioc-4")).isFalse();
    }

    private static IocEntity entity(String id, String type, String value) {
        IocEntity entity = new IocEntity();
        entity.setId(id);
        entity.setTenantId("tenant");
        entity.setType(type);
        entity.setValue(value);
        entity.setSeverity("HIGH");
        entity.setSource("test");
        entity.setDescription("description");
        entity.setTagsJson("[\"c2\"]");
        entity.setFirstSeen(Instant.EPOCH);
        entity.setLastSeen(Instant.EPOCH);
        return entity;
    }
}
