package com.socp.notify.web.api.controller;

import com.socp.notify.web.api.request.ChannelCreateRequest;
import com.socp.notify.web.api.request.NotifyAlarmRequest;
import com.socp.notify.web.domain.Channel;
import com.socp.notify.web.persistence.store.ChannelStore;
import com.socp.notify.web.service.NotificationDispatcher;
import com.socp.platform.tenant.context.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class NotifyControllerTest {

    @Mock private ChannelStore channels;
    @Mock private NotificationDispatcher dispatcher;

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void channelListingDelegatesToTenantScopedStore() {
        TenantContext.set("tenant-a");
        List<Channel> expected = List.of(new Channel("CH-1", "Ops", "LOG", "local", true, ""));
        given(channels.list()).willReturn(expected);

        assertSame(expected, controller().channels());
        verify(channels).list();
    }

    @Test
    void createTrimsUserInputAndDefaultsEnabledToTrue() {
        TenantContext.set("tenant-a");
        ChannelCreateRequest request = new ChannelCreateRequest(
                "  Ops  ", "webhook", "  http://ops  ", null, "description");
        Channel saved = new Channel("CH-1", "Ops", "WEBHOOK", "http://ops", true, "description");
        ArgumentCaptor<Channel> captor = ArgumentCaptor.forClass(Channel.class);
        given(channels.add(any(Channel.class))).willReturn(saved);

        Channel result = controller().create(request);

        assertSame(saved, result);
        verify(channels).add(captor.capture());
        Channel submitted = captor.getValue();
        assertEquals("Ops", submitted.name());
        assertEquals("WEBHOOK", submitted.type());
        assertEquals("http://ops", submitted.target());
        assertEquals(true, submitted.enabled());
    }

    @Test
    void toggleReturnsNotFoundWithoutWritingWhenChannelIsMissing() {
        TenantContext.set("tenant-a");
        given(channels.get("missing")).willReturn(null);

        assertEquals(Map.of("error", "not_found"), controller().toggle("missing"));
    }

    @Test
    void toggleInvertsEnabledStateAndPersistsUpdatedChannel() {
        TenantContext.set("tenant-a");
        Channel existing = new Channel("CH-1", "Ops", "LOG", "local", true, "notes");
        given(channels.get("CH-1")).willReturn(existing);
        ArgumentCaptor<Channel> captor = ArgumentCaptor.forClass(Channel.class);

        Map<String, Object> result = controller().toggle("CH-1");

        verify(channels).add(captor.capture());
        Channel updated = captor.getValue();
        assertEquals(false, updated.enabled());
        assertSame(updated, result.get("channel"));
    }

    @Test
    void deleteReturnsStoreOutcomeAndIdentifier() {
        TenantContext.set("tenant-a");
        given(channels.delete("CH-1")).willReturn(true);

        assertEquals(Map.of("removed", true, "id", "CH-1"), controller().delete("CH-1"));
    }

    @Test
    void notifyReturnsBadGatewayWhenOneChannelFails() {
        TenantContext.set("tenant-a");
        NotifyAlarmRequest request = new NotifyAlarmRequest();
        request.setId("AL-1");
        Map<String, Object> result = Map.of("alarmId", "AL-1", "failed", 1);
        given(dispatcher.dispatch(any())).willReturn(result);

        var response = controller().notify(request);

        assertEquals(HttpStatus.BAD_GATEWAY, response.getStatusCode());
        assertSame(result, response.getBody());
    }

    @Test
    void notifyReturnsOkWhenAllChannelsSucceedAndLogDelegates() {
        TenantContext.set("tenant-a");
        NotifyAlarmRequest request = new NotifyAlarmRequest();
        request.setId("AL-2");
        Map<String, Object> result = Map.of("alarmId", "AL-2", "failed", 0);
        List<Map<String, Object>> log = List.of(Map.of("alarmId", "AL-2", "status", "sent"));
        given(dispatcher.dispatch(any())).willReturn(result);
        given(dispatcher.log()).willReturn(log);

        var response = controller().notify(request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertSame(log, controller().log());
        verify(dispatcher).log();
    }

    private NotifyController controller() {
        return new NotifyController(channels, dispatcher);
    }
}
