package com.socp.threat.web.persistence.store;


import com.socp.threat.web.persistence.repository.IocRepository;
import com.socp.threat.web.persistence.entity.IocEntity;
import com.socp.threat.web.domain.Ioc;
import com.socp.platform.tenant.context.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

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

    @Test
    void upsertPreservesObservationWindowAcrossFeedRefreshes() {
        TenantContext.set("tenant-d");
        IocEntity existing = entity("ioc-4", "DOMAIN", "example.com");
        existing.setFirstSeen(Instant.parse("2026-01-01T00:00:00Z"));
        existing.setLastSeen(Instant.parse("2026-01-10T00:00:00Z"));
        when(repository.findByTenantIdAndSourceAndExternalId("tenant-d", "feed", "stix--1"))
                .thenReturn(Optional.of(existing));
        when(repository.save(any(IocEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        IocStore store = new IocStore(repository, false);

        Ioc incoming = new Ioc("new-id", "DOMAIN", "example.com", "HIGH", "feed", "refresh",
                List.of("stix"), Instant.parse("2026-01-05T00:00:00Z"),
                Instant.parse("2026-01-20T00:00:00Z"), "feed", "stix--1", 80d, "TLP:AMBER",
                Instant.parse("2026-01-01T00:00:00Z"), null, null, false, "taxii");
        Ioc persisted = store.add(incoming);

        assertThat(persisted.firstSeen()).isEqualTo(Instant.parse("2026-01-01T00:00:00Z"));
        assertThat(persisted.lastSeen()).isEqualTo(Instant.parse("2026-01-20T00:00:00Z"));
        verify(repository).save(existing);
    }

    @Test
    void revokedAndExpiredIndicatorsNeverEnrich() {
        TenantContext.set("tenant-e");
        IocEntity revoked = entity("ioc-5", "IP", "203.0.113.9");
        revoked.setRevoked(true);
        IocEntity expired = entity("ioc-6", "IP", "203.0.113.10");
        expired.setExpiration(Instant.now().minusSeconds(60));
        when(repository.findByTenantIdAndValue("tenant-e", "203.0.113.9")).thenReturn(Optional.of(revoked));
        when(repository.findByTenantIdAndValue("tenant-e", "203.0.113.10")).thenReturn(Optional.of(expired));
        IocStore store = new IocStore(repository, false);

        assertThat(store.match("203.0.113.9")).isNull();
        assertThat(store.match("203.0.113.10")).isNull();
    }

    @Test
    void boundsInMemoryCacheCardinality() {
        TenantContext.set("tenant-cache");
        when(repository.findByTenantIdAndValueIn(eq("tenant-cache"), anyList()))
                .thenReturn(List.of(
                        entity("ioc-a", "DOMAIN", "a.example"),
                        entity("ioc-b", "DOMAIN", "b.example"),
                        entity("ioc-c", "DOMAIN", "c.example")));
        IocStore store = new IocStore(repository, false);
        ReflectionTestUtils.setField(store, "maxCacheEntries", 2);

        store.matchAll(List.of("a.example", "b.example", "c.example"));

        assertThat(store.cachedEntries()).isLessThanOrEqualTo(2);
    }

    @Test
    void cleanupDeletesExpiredRowsButKeepsRevokedEvidence() {
        TenantContext.openSystem().close();
        when(repository.deleteByExpirationBeforeAndRevokedFalse(any(Instant.class))).thenReturn(2L);
        when(repository.deleteByValidUntilBeforeAndRevokedFalse(any(Instant.class))).thenReturn(1L);
        IocStore store = new IocStore(repository, false);

        store.cleanupExpired();

        verify(repository).deleteByExpirationBeforeAndRevokedFalse(any(Instant.class));
        verify(repository).deleteByValidUntilBeforeAndRevokedFalse(any(Instant.class));
    }

    @Test
    void seedsDefaultTenantOnlyWhenDemoDataIsEnabled() {
        TenantContext.clear();
        when(repository.countByTenantId("default")).thenReturn(0L);
        IocStore store = new IocStore(repository, true);

        store.seed();

        verify(repository, times(8)).save(any(IocEntity.class));
        verify(repository).countByTenantId("default");
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
