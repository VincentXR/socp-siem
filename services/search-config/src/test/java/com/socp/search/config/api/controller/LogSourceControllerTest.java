package com.socp.search.config.api.controller;

import com.socp.platform.tenant.context.TenantContext;
import com.socp.platform.error.api.PageResponse;
import com.socp.search.config.config.IngestLimitsProperties;
import com.socp.search.config.domain.LogSource;
import com.socp.search.config.persistence.entity.LogSourceEntity;
import com.socp.search.config.persistence.repository.LogSourceRepository;
import com.socp.search.config.persistence.store.LogSourceStore;
import com.socp.search.config.persistence.store.SinkTargetStore;
import com.socp.search.config.service.IngestPipeline;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LogSourceControllerTest {

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void seedUsesDefaultTenantWithoutLeakingContext() {
        LogSourceRepository repository = mock(LogSourceRepository.class);
        when(repository.findByTenantId(any())).thenReturn(List.of());
        LogSourceController controller = controller(repository);

        controller.seed();

        verify(repository, atLeastOnce()).findByTenantId("default");
        ArgumentCaptor<LogSourceEntity> saved = ArgumentCaptor.forClass(LogSourceEntity.class);
        verify(repository, atLeastOnce()).save(saved.capture());
        saved.getAllValues().forEach(entity -> assertEquals("default", entity.getTenantId()));
        assertNull(TenantContext.get());
    }

    @Test
    void seedRestoresExistingTenantContext() {
        LogSourceRepository repository = mock(LogSourceRepository.class);
        when(repository.findByTenantId(any())).thenReturn(List.of());
        LogSourceController controller = controller(repository);
        TenantContext.set("tenant-a");

        controller.seed();

        assertEquals("tenant-a", TenantContext.get());
    }

    @Test
    void seedRestoresExistingTenantContextWhenPersistenceFails() {
        LogSourceRepository repository = mock(LogSourceRepository.class);
        when(repository.findByTenantId(any())).thenThrow(new IllegalStateException("db unavailable"));
        LogSourceController controller = controller(repository);
        TenantContext.set("tenant-a");

        assertThrows(IllegalStateException.class, controller::seed);

        assertEquals("tenant-a", TenantContext.get());
    }

    @Test
    void pagedCatalogueUsesDatabasePageAndSharedEnvelope() {
        TenantContext.set("tenant-a");
        LogSourceEntity entity = new LogSourceEntity();
        entity.setTenantId("tenant-a");
        entity.setStorageId("storage-source");
        entity.setId("source");
        entity.setName("source");
        entity.setType("FILE");
        entity.setFormat("AUTO");
        entity.setPath("/var/log/source");
        entity.setEnabled(true);
        LogSourceRepository repository = mock(LogSourceRepository.class);
        when(repository.findByTenantId("tenant-a", Pageable.ofSize(1)))
                .thenReturn(new PageImpl<>(List.of(entity), Pageable.ofSize(1), 2));
        LogSourceController controller = controller(repository);

        @SuppressWarnings("unchecked")
        PageResponse<LogSource> result =
                (PageResponse<LogSource>) controller.list(1, 1).data();

        assertEquals(1, result.items().size());
        assertEquals(2, result.total());
        assertEquals(1, result.page());
        assertEquals(1, result.size());
    }

    private LogSourceController controller(LogSourceRepository repository) {
        return new LogSourceController(
                new LogSourceStore(repository),
                mock(SinkTargetStore.class),
                mock(IngestPipeline.class),
                new IngestLimitsProperties());
    }
}
