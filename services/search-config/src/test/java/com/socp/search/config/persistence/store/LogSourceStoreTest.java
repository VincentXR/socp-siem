package com.socp.search.config.persistence.store;

import com.socp.platform.tenant.context.TenantContext;
import com.socp.search.config.domain.LogSource;
import com.socp.search.config.domain.ParseFormat;
import com.socp.search.config.domain.SourceType;
import com.socp.search.config.persistence.entity.LogSourceEntity;
import com.socp.search.config.persistence.repository.LogSourceRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LogSourceStoreTest {

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void resolvesCollectorTagsAndTracksSourceMutations() {
        TenantContext.set("tenant-source-store");
        LogSource source = LogSource.create(
                "Nginx access", SourceType.FILE, ParseFormat.AUTO,
                "/var/log/nginx/access.log", null, null, null, true);
        LogSourceEntity entity = LogSourceStore.toEntity(source);
        LogSourceRepository repository = mock(LogSourceRepository.class);
        when(repository.findByTenantId("tenant-source-store")).thenReturn(List.of(entity));
        when(repository.findIdentityProjections("tenant-source-store"))
                .thenReturn(List.<Object[]>of(new Object[]{source.id(), source.name()}));
        when(repository.findByTenantIdAndSourceId("tenant-source-store", source.id()))
                .thenReturn(Optional.of(entity));
        LogSourceStore store = new LogSourceStore(repository);

        assertThat(store.findByCollectorTag(null)).isEmpty();
        assertThat(store.findByCollectorTag(" ")).isEmpty();
        assertThat(store.findByCollectorTag(source.collectorTag())).contains(source);
        assertThat(store.findByCollectorTag("missing-tag")).isEmpty();
        // The tag lookup reads an identity projection instead of deserialising the catalogue.
        verify(repository, never()).findByTenantId("tenant-source-store");

        store.save(source);
        assertThat(store.revision("tenant-source-store")).isEqualTo(1L);
        assertThat(store.delete(source.id())).isTrue();
        assertThat(store.revision("tenant-source-store")).isEqualTo(2L);
    }

    @Test
    void sourceChangeTokensAreScopedToTheWritingTenant() {
        TenantContext.set("tenant-a");
        LogSourceRepository repository = mock(LogSourceRepository.class);
        LogSourceStore store = new LogSourceStore(repository);
        LogSource source = LogSource.create("tenant-a-source", SourceType.FILE, ParseFormat.AUTO,
                "/var/log/a.log", null, null, null, true);
        LogSourceEntity entity = LogSourceStore.toEntity(source);
        when(repository.findByTenantIdAndSourceId("tenant-a", source.id())).thenReturn(Optional.of(entity));

        store.save(source);

        assertThat(store.revision("tenant-a")).isEqualTo(1L);
        assertThat(store.revision("tenant-b")).isZero();
    }

    @Test
    void reportsMissingSourceWithoutAdvancingRevision() {
        TenantContext.set("tenant-source-store");
        LogSourceRepository repository = mock(LogSourceRepository.class);
        when(repository.findByTenantIdAndSourceId("tenant-source-store", "missing"))
                .thenReturn(Optional.empty());
        LogSourceStore store = new LogSourceStore(repository);

        assertThat(store.delete("missing")).isFalse();
        assertThat(store.revision("tenant-source-store")).isZero();
    }
}
