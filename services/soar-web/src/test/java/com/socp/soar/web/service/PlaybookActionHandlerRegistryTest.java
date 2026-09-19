package com.socp.soar.web.service;

import com.socp.platform.client.service.IncidentClient;
import com.socp.platform.client.service.NotifyClient;
import com.socp.platform.client.http.ServiceCall;
import com.socp.platform.client.http.SocpHttpClient;
import com.socp.platform.client.service.SocpService;
import com.socp.soar.web.config.SoarActionConnectorProperties;
import com.socp.soar.web.domain.PlaybookActionType;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlaybookActionHandlerRegistryTest {

    @Test
    void firewallConnectorRequiresAnAcceptedReceipt() {
        SocpHttpClient http = mock(SocpHttpClient.class);
        SoarActionConnectorProperties properties = new SoarActionConnectorProperties();
        properties.setFirewallBlockUrl("https://firewall.example.test/block");
        PlaybookActionHandlerRegistry registry = new PlaybookActionHandlerRegistry(
                mock(NotifyClient.class), mock(IncidentClient.class), http, properties);
        when(http.postExternal(eq("https://firewall.example.test/block"), any(),
                eq(SocpHttpClient.JSON), eq(5000))).thenReturn(call("{}"));

        Map<String, Object> result = registry.find(PlaybookActionType.FIREWALL_BLOCK).handle(
                new PlaybookActionContext("firewall-block", Map.of("id", "AL-1"), "soar-key-1", false));

        assertEquals("failed", result.get("status"));
        assertEquals("MISSING_CONNECTOR_RECEIPT", result.get("errorCode"));
        assertEquals("EXECUTED", result.get("mode"));
    }

    @Test
    void firewallConnectorExposesVerifiedOperationId() {
        SocpHttpClient http = mock(SocpHttpClient.class);
        SoarActionConnectorProperties properties = new SoarActionConnectorProperties();
        properties.setFirewallBlockUrl("https://firewall.example.test/block");
        PlaybookActionHandlerRegistry registry = new PlaybookActionHandlerRegistry(
                mock(NotifyClient.class), mock(IncidentClient.class), http, properties);
        when(http.postExternal(eq("https://firewall.example.test/block"), any(),
                eq(SocpHttpClient.JSON), eq(5000))).thenReturn(call(
                "{\"accepted\":true,\"operationId\":\"fw-123\"}"));

        Map<String, Object> result = registry.find(PlaybookActionType.FIREWALL_BLOCK).handle(
                new PlaybookActionContext("firewall-block", Map.of("id", "AL-1"), "soar-key-1", false));

        assertEquals("executed", result.get("status"));
        assertEquals("EXECUTED", result.get("mode"));
        assertEquals("fw-123", result.get("operationId"));
        verify(http).postExternal(eq("https://firewall.example.test/block"), any(),
                eq(SocpHttpClient.JSON), eq(5000));
    }

    @Test
    void caseActionRejectsAnErrorSignalCarriedByASuccessEnvelope() {
        IncidentClient incidents = mock(IncidentClient.class);
        PlaybookActionHandlerRegistry registry = new PlaybookActionHandlerRegistry(
                mock(NotifyClient.class), incidents, mock(SocpHttpClient.class));
        when(incidents.createFromAlarm(any())).thenReturn(new ServiceCall(SocpService.INCIDENT,
                "http://incident-web", true, 200,
                "{\"code\":0,\"message\":\"ok\",\"data\":{\"error\":\"not_found\"}}", null, 3, false, 1));

        Map<String, Object> result = registry.find(PlaybookActionType.CASE).handle(
                new PlaybookActionContext("create-case", Map.of("id", "AL-1"), "soar-key-1", false));

        assertEquals("failed", result.get("status"));
        assertEquals("DOWNSTREAM_ERROR_RECEIPT", result.get("errorCode"));
        assertEquals(Boolean.FALSE, result.get("verified"));
    }

    @Test
    void notifyActionRejectsAnErrorSignalCarriedByASuccessEnvelope() {
        NotifyClient notify = mock(NotifyClient.class);
        PlaybookActionHandlerRegistry registry = new PlaybookActionHandlerRegistry(
                notify, mock(IncidentClient.class), mock(SocpHttpClient.class));
        when(notify.notifyAlert(any())).thenReturn(new ServiceCall(SocpService.NOTIFY,
                "http://notify-web", true, 200,
                "{\"code\":0,\"message\":\"ok\",\"data\":{\"failed\":0,\"error\":\"not_found\"}}",
                null, 3, false, 1));

        Map<String, Object> result = registry.find(PlaybookActionType.NOTIFY).handle(
                new PlaybookActionContext("notify", Map.of("id", "AL-1"), "soar-key-1", false));

        assertEquals("failed", result.get("status"));
        assertEquals("DOWNSTREAM_ERROR_RECEIPT", result.get("errorCode"));
    }

    @Test
    void notifyActionVerifiesAReceiptWithoutErrorSignal() {
        NotifyClient notify = mock(NotifyClient.class);
        PlaybookActionHandlerRegistry registry = new PlaybookActionHandlerRegistry(
                notify, mock(IncidentClient.class), mock(SocpHttpClient.class));
        when(notify.notifyAlert(any())).thenReturn(call(
                "{\"code\":0,\"message\":\"ok\",\"data\":{\"failed\":0}}"));

        Map<String, Object> result = registry.find(PlaybookActionType.NOTIFY).handle(
                new PlaybookActionContext("notify", Map.of("id", "AL-1"), "soar-key-1", false));

        assertEquals("executed", result.get("status"));
        assertEquals(Boolean.TRUE, result.get("verified"));
    }

    private static ServiceCall call(String body) {
        return new ServiceCall(SocpService.NOTIFY, "https://firewall.example.test/block", true,
                200, body, null, 3, false, 1);
    }
}
