package com.socp.soar.web.service;

import com.socp.platform.client.IncidentClient;
import com.socp.platform.client.NotifyClient;
import com.socp.platform.client.ServiceCall;
import com.socp.platform.client.SocpHttpClient;
import com.socp.platform.client.SocpService;
import com.socp.soar.web.config.SoarActionConnectorProperties;
import com.socp.soar.web.model.PlaybookActionType;
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

    private static ServiceCall call(String body) {
        return new ServiceCall(SocpService.NOTIFY, "https://firewall.example.test/block", true,
                200, body, null, 3, false, 1);
    }
}
