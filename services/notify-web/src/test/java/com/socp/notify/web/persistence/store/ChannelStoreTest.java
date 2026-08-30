package com.socp.notify.web.persistence.store;

import com.socp.notify.web.persistence.entity.ChannelEntity;
import com.socp.notify.web.domain.Channel;
import com.socp.notify.web.persistence.repository.ChannelRepository;
import com.socp.platform.tenant.context.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
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

    @Test
    void disabledDemoDataDoesNotTouchRepositoryDuringSeed() {
        ChannelStore store = new ChannelStore(repository, false);

        store.seed();

        verify(repository, times(0)).findByTenantId(any());
        verify(repository, times(0)).save(any());
    }

    @Test
    void existingDefaultChannelsAreNotSeededTwice() {
        when(repository.findByTenantId("default"))
                .thenReturn(List.of(new ChannelEntity("CH-1", "Ops", "LOG", "local", true, "")));
        ChannelStore store = new ChannelStore(repository, true);

        store.seed();

        verify(repository).findByTenantId("default");
        verify(repository, times(0)).save(any());
    }

    @Test
    void addListGetDeleteAndEnabledRespectCurrentTenant() {
        TenantContext.set("tenant-a");
        ChannelStore store = new ChannelStore(repository, false);
        Channel channel = new Channel("CH-1", "Ops", "LOG", "local", true, "notes");
        ArgumentCaptor<ChannelEntity> saved = ArgumentCaptor.forClass(ChannelEntity.class);
        given(repository.findByTenantId("tenant-a")).willReturn(List.of(
                new ChannelEntity("CH-1", "Ops", "LOG", "local", true, "notes"),
                new ChannelEntity("CH-2", "Mail", "EMAIL", "mail", false, "")));
        given(repository.findByIdAndTenantId("CH-1", "tenant-a"))
                .willReturn(Optional.of(new ChannelEntity("CH-1", "Ops", "LOG", "local", true, "notes")));
        given(repository.findByIdAndTenantId("missing", "tenant-a"))
                .willReturn(Optional.empty());

        assertSame(channel, store.add(channel));
        verify(repository).save(saved.capture());
        assertEquals("tenant-a", saved.getValue().getTenantId());
        assertEquals("CH-1", saved.getValue().getId());

        assertEquals(2, store.list().size());
        assertEquals("Ops", store.get("CH-1").name());
        assertEquals(1, store.enabled().size());
        assertFalse(store.delete("missing"));
        assertEquals(true, store.delete("CH-1"));
        verify(repository).delete(any(ChannelEntity.class));
    }
}
