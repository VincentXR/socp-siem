package com.socp.notify.web.persistence.store;

import com.socp.notify.web.persistence.entity.ChannelEntity;
import com.socp.notify.web.persistence.repository.ChannelRepository;
import com.socp.platform.tenant.context.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChannelStoreTest {

    @Mock
    private ChannelRepository repository;

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void seedsConfiguredDefaultChannelsInsideDefaultTenantScope() {
        when(repository.findByTenantId("default")).thenReturn(List.of());
        ChannelStore store = new ChannelStore(repository, true);

        store.seed();

        verify(repository).findByTenantId("default");
        verify(repository, times(3)).save(any(ChannelEntity.class));
    }
}
