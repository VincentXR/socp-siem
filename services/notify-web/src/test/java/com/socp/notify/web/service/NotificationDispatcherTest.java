package com.socp.notify.web.service;

import com.socp.notify.web.domain.Channel;
import com.socp.notify.web.store.ChannelStore;
import com.socp.notify.web.store.NotificationDeliveryEntity;
import com.socp.notify.web.store.NotificationDeliveryRepository;
import com.socp.platform.client.ServiceCall;
import com.socp.platform.client.SocpHttpClient;
import com.socp.platform.client.SocpService;
import com.socp.platform.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class NotificationDispatcherTest {

    @Mock private ChannelStore channels;
    @Mock private SocpHttpClient http;
    @Mock private NotificationDeliveryRepository deliveries;

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void successfulChannelIsPersistedAsAnIdempotencyReceipt() {
        TenantContext.set("tenant-a");
        Channel channel = new Channel("CH-1", "Ops", "WEBHOOK", "http://ops", true, "");
        given(channels.enabled()).willReturn(List.of(channel));
        given(deliveries.findByIdAndTenantId(any(), eq("tenant-a"))).willReturn(Optional.empty());
        given(http.postExternal(eq("http://ops"), any(), eq(SocpHttpClient.JSON), eq(3000)))
                .willReturn(ok());
        NotificationDispatcher dispatcher = new NotificationDispatcher(channels, http, deliveries);

        Map<String, Object> result = dispatcher.dispatch(Map.of("id", "AL-1", "severity", "HIGH"));

        assertEquals(0, result.get("failed"));
        ArgumentCaptor<NotificationDeliveryEntity> receipt = ArgumentCaptor.forClass(NotificationDeliveryEntity.class);
        verify(deliveries).save(receipt.capture());
        assertEquals("tenant-a", receipt.getValue().getTenantId());
        assertEquals("AL-1", receipt.getValue().getAlarmId());
    }

    @Test
    void replayUsesReceiptAndDoesNotSendChannelAgain() {
        TenantContext.set("tenant-a");
        Channel channel = new Channel("CH-1", "Ops", "WEBHOOK", "http://ops", true, "");
        NotificationDeliveryEntity receipt = new NotificationDeliveryEntity();
        receipt.setResultJson("{\"channel\":\"Ops\",\"status\":\"sent\"}");
        receipt.setDeliveredAt(Instant.now());
        given(channels.enabled()).willReturn(List.of(channel));
        given(deliveries.findByIdAndTenantId(any(), eq("tenant-a"))).willReturn(Optional.of(receipt));
        NotificationDispatcher dispatcher = new NotificationDispatcher(channels, http, deliveries);

        Map<String, Object> result = dispatcher.dispatch(Map.of("id", "AL-1"));

        List<?> channelResults = (List<?>) result.get("results");
        assertTrue((Boolean) ((Map<?, ?>) channelResults.getFirst()).get("duplicate"));
        verify(http, never()).postExternal(any(), any(), any(), any(Integer.class));
    }

    @Test
    void failedChannelIsNotReceiptedSoAlertDeliveryCanRetryIt() {
        TenantContext.set("tenant-a");
        Channel channel = new Channel("CH-1", "Ops", "WEBHOOK", "http://ops", true, "");
        given(channels.enabled()).willReturn(List.of(channel));
        given(deliveries.findByIdAndTenantId(any(), eq("tenant-a"))).willReturn(Optional.empty());
        given(http.postExternal(eq("http://ops"), any(), eq(SocpHttpClient.JSON), eq(3000)))
                .willReturn(new ServiceCall(SocpService.NOTIFY, "http://ops", false,
                        503, "", "unavailable", 1, true, 1));
        NotificationDispatcher dispatcher = new NotificationDispatcher(channels, http, deliveries);

        Map<String, Object> result = dispatcher.dispatch(Map.of("id", "AL-1"));

        assertEquals(1, result.get("failed"));
        verify(deliveries, never()).save(any());
    }

    @Test
    void unsupportedEmailChannelIsFailedAndNeverReceiptedAsDelivered() {
        TenantContext.set("tenant-a");
        Channel channel = new Channel("CH-EMAIL", "Mail", "EMAIL", "soc@example.com", true, "");
        given(channels.enabled()).willReturn(List.of(channel));
        given(deliveries.findByIdAndTenantId(any(), eq("tenant-a"))).willReturn(Optional.empty());
        NotificationDispatcher dispatcher = new NotificationDispatcher(channels, http, deliveries);

        Map<String, Object> result = dispatcher.dispatch(Map.of("id", "AL-1"));

        assertEquals(1, result.get("failed"));
        List<?> results = (List<?>) result.get("results");
        assertEquals("failed", ((Map<?, ?>) results.getFirst()).get("status"));
        verify(deliveries, never()).save(any());
        verify(http, never()).postExternal(any(), any(), any(), any(Integer.class));
    }

    private static ServiceCall ok() {
        return new ServiceCall(SocpService.NOTIFY, "http://ops", true,
                200, "ok", null, 1, false, 1);
    }
}
