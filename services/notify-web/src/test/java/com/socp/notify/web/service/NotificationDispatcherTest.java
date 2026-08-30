package com.socp.notify.web.service;

import com.socp.notify.web.domain.Channel;
import com.socp.notify.web.persistence.entity.NotificationDeliveryEntity;
import com.socp.notify.web.persistence.entity.NotificationDispatchLogEntity;
import com.socp.notify.web.persistence.store.ChannelStore;
import com.socp.notify.web.persistence.repository.NotificationDeliveryRepository;
import com.socp.notify.web.persistence.repository.NotificationDispatchLogRepository;
import com.socp.platform.client.http.ServiceCall;
import com.socp.platform.client.http.SocpHttpClient;
import com.socp.platform.client.service.SocpService;
import com.socp.platform.tenant.context.TenantContext;
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
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class NotificationDispatcherTest {

    @Mock private ChannelStore channels;
    @Mock private SocpHttpClient http;
    @Mock private NotificationDeliveryRepository deliveries;
    @Mock private NotificationDispatchLogRepository dispatchLogs;
    @Mock private SmtpNotificationSender smtpSender;

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
        NotificationDispatcher dispatcher = dispatcher();

        Map<String, Object> result = dispatcher.dispatch(Map.of("id", "AL-1", "severity", "HIGH"));

        assertEquals(0, result.get("failed"));
        ArgumentCaptor<NotificationDeliveryEntity> receipt = ArgumentCaptor.forClass(NotificationDeliveryEntity.class);
        verify(deliveries).save(receipt.capture());
        assertEquals("tenant-a", receipt.getValue().getTenantId());
        assertEquals("AL-1", receipt.getValue().getAlarmId());
    }

    @Test
    void logChannelRecordsDeliveryWithoutInvokingExternalConnector() {
        TenantContext.set("tenant-a");
        Channel channel = new Channel("CH-LOG", "Local evidence", "LOG", "golden-demo", true, "");
        given(channels.enabled()).willReturn(List.of(channel));
        given(deliveries.findByIdAndTenantId(any(), eq("tenant-a"))).willReturn(Optional.empty());
        NotificationDispatcher dispatcher = dispatcher();

        Map<String, Object> result = dispatcher.dispatch(Map.of("id", "AL-1"));

        assertEquals(0, result.get("failed"));
        List<?> channelResults = (List<?>) result.get("results");
        assertEquals("logged", ((Map<?, ?>) channelResults.getFirst()).get("status"));
        verify(http, never()).postExternal(any(), any(), any(), any(Integer.class));
        verify(deliveries).save(any());
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
        NotificationDispatcher dispatcher = dispatcher();

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
        NotificationDispatcher dispatcher = dispatcher();

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
        NotificationDispatcher dispatcher = dispatcher();

        Map<String, Object> result = dispatcher.dispatch(Map.of("id", "AL-1"));

        assertEquals(1, result.get("failed"));
        List<?> results = (List<?>) result.get("results");
        assertEquals("failed", ((Map<?, ?>) results.getFirst()).get("status"));
        verify(deliveries, never()).save(any());
        verify(http, never()).postExternal(any(), any(), any(), any(Integer.class));
    }

    @Test
    void missingOrBlankAlarmIdIsRejectedBeforeDispatch() {
        assertThrows(IllegalArgumentException.class, () -> dispatcher().dispatch(Map.of()));
        assertThrows(IllegalArgumentException.class,
                () -> dispatcher().dispatch(Map.of("id", "  ")));
        verify(channels, never()).enabled();
    }

    @Test
    void noEnabledChannelsReturnsAnEmptySuccessfulDispatch() {
        TenantContext.set("tenant-a");
        given(channels.enabled()).willReturn(List.of());

        Map<String, Object> result = dispatcher().dispatch(Map.of("id", "AL-EMPTY", "ruleId", "R-1"));

        assertEquals("AL-EMPTY", result.get("alarmId"));
        assertEquals("R-1", result.get("ruleId"));
        assertEquals(0, result.get("dispatched"));
        assertEquals(0, result.get("failed"));
        assertEquals(List.of(), result.get("results"));
        verify(deliveries, never()).findByIdAndTenantId(any(), any());
    }

    @Test
    void nullHttpResponseIsReportedAsFailedAndCanBeRetried() {
        TenantContext.set("tenant-a");
        Channel channel = new Channel("CH-NULL", "Ops", "WEBHOOK", "http://ops", true, "");
        given(channels.enabled()).willReturn(List.of(channel));
        given(deliveries.findByIdAndTenantId(any(), eq("tenant-a"))).willReturn(Optional.empty());
        given(http.postExternal(eq("http://ops"), any(), eq(SocpHttpClient.JSON), eq(3000)))
                .willReturn(null);

        Map<String, Object> result = dispatcher().dispatch(Map.of("id", "AL-NULL"));

        assertEquals(1, result.get("failed"));
        Map<?, ?> channelResult = (Map<?, ?>) ((List<?>) result.get("results")).getFirst();
        assertEquals("failed", channelResult.get("status"));
        assertEquals(0, channelResult.get("httpStatus"));
        verify(deliveries, never()).save(any());
    }

    @Test
    void connectorExceptionIsContainedToTheChannelResult() {
        TenantContext.set("tenant-a");
        Channel channel = new Channel("CH-ERR", "Ops", "WEBHOOK", "http://ops", true, "");
        given(channels.enabled()).willReturn(List.of(channel));
        given(deliveries.findByIdAndTenantId(any(), eq("tenant-a"))).willReturn(Optional.empty());
        given(http.postExternal(eq("http://ops"), any(), eq(SocpHttpClient.JSON), eq(3000)))
                .willThrow(new IllegalStateException("connector down"));

        Map<String, Object> result = dispatcher().dispatch(Map.of("id", "AL-ERR"));

        assertEquals(1, result.get("failed"));
        Map<?, ?> channelResult = (Map<?, ?>) ((List<?>) result.get("results")).getFirst();
        assertEquals("failed", channelResult.get("status"));
        assertTrue(String.valueOf(channelResult.get("error")).contains("connector down"));
        verify(deliveries, never()).save(any());
    }

    @Test
    void instantMessageChannelsUseStructuredTextPayload() {
        TenantContext.set("tenant-a");
        Channel channel = new Channel("CH-SLACK", "Slack", "SLACK", "http://slack", true, "");
        given(channels.enabled()).willReturn(List.of(channel));
        given(deliveries.findByIdAndTenantId(any(), eq("tenant-a"))).willReturn(Optional.empty());
        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        given(http.postExternal(eq("http://slack"), payload.capture(), eq(SocpHttpClient.JSON), eq(3000)))
                .willReturn(ok());

        Map<String, Object> result = dispatcher().dispatch(Map.of(
                "id", "AL-SLACK", "severity", "HIGH", "ruleName", "Suspicious login",
                "mitre", "T1078", "entity", "alice", "message", "bad password",
                "occurredAt", "2026-08-30T12:00:00Z"));

        assertEquals(0, result.get("failed"));
        assertTrue(payload.getValue().contains("\"text\""));
        assertTrue(payload.getValue().contains("Suspicious login"));
        assertTrue(payload.getValue().contains("T1078"));
    }

    @Test
    void unknownConnectorTypeUsesGenericPayloadAndTruncatesLongFailure() {
        TenantContext.set("tenant-a");
        Channel channel = new Channel("CH-OTHER", "Other", "PAGER", "http://pager", true, "");
        given(channels.enabled()).willReturn(List.of(channel));
        given(deliveries.findByIdAndTenantId(any(), eq("tenant-a"))).willReturn(Optional.empty());
        String longBody = "x".repeat(400);
        given(http.postExternal(eq("http://pager"), any(), eq(SocpHttpClient.JSON), eq(3000)))
                .willReturn(new ServiceCall(SocpService.NOTIFY, "http://pager", false,
                        500, longBody, "remote failure", 1, true, 1));

        Map<String, Object> result = dispatcher().dispatch(Map.of("id", "AL-PAGER"));

        Map<?, ?> channelResult = (Map<?, ?>) ((List<?>) result.get("results")).getFirst();
        assertEquals("failed", channelResult.get("status"));
        assertTrue(String.valueOf(channelResult.get("detail")).endsWith("..."));
        verify(http).postExternal(eq("http://pager"),
                org.mockito.ArgumentMatchers.argThat(body -> body.contains("\"alarm\"")),
                eq(SocpHttpClient.JSON), eq(3000));
    }

    @Test
    void smtpSuccessIsReceiptedAndReceivesRenderedAlarmText() {
        TenantContext.set("tenant-a");
        Channel channel = new Channel("CH-MAIL", "Mail", "EMAIL", " soc@example.com ", true, "");
        given(channels.enabled()).willReturn(List.of(channel));
        given(deliveries.findByIdAndTenantId(any(), eq("tenant-a"))).willReturn(Optional.empty());
        given(smtpSender.send(eq(" soc@example.com "), eq("SOCP security alarm: AL-MAIL"), any()))
                .willReturn(new SmtpNotificationSender.DeliveryResult(true, null, "accepted"));

        Map<String, Object> result = dispatcher(smtpSender).dispatch(Map.of(
                "id", "AL-MAIL", "severity", "HIGH", "message", "suspicious"));

        assertEquals(0, result.get("failed"));
        assertEquals("sent", ((Map<?, ?>) ((List<?>) result.get("results")).getFirst()).get("status"));
        verify(deliveries).save(any());
        verify(smtpSender).send(eq(" soc@example.com "), eq("SOCP security alarm: AL-MAIL"),
                org.mockito.ArgumentMatchers.argThat(text -> text.contains("suspicious")));
    }

    @Test
    void smtpFailureIncludesConnectorErrorAndIsNotReceipted() {
        TenantContext.set("tenant-a");
        Channel channel = new Channel("CH-MAIL", "Mail", "EMAIL", "soc@example.com", true, "");
        given(channels.enabled()).willReturn(List.of(channel));
        given(deliveries.findByIdAndTenantId(any(), eq("tenant-a"))).willReturn(Optional.empty());
        given(smtpSender.send(any(), any(), any()))
                .willReturn(new SmtpNotificationSender.DeliveryResult(false, "SMTP_SEND_FAILED", "rejected"));

        Map<String, Object> result = dispatcher(smtpSender).dispatch(Map.of("id", "AL-MAIL-FAIL"));

        Map<?, ?> channelResult = (Map<?, ?>) ((List<?>) result.get("results")).getFirst();
        assertEquals(1, result.get("failed"));
        assertEquals("SMTP_SEND_FAILED", channelResult.get("errorCode"));
        verify(deliveries, never()).save(any());
    }

    @Test
    void corruptReceiptIsReturnedAsFailedInsteadOfSendingAgain() {
        TenantContext.set("tenant-a");
        Channel channel = new Channel("CH-CORRUPT", "Ops", "WEBHOOK", "http://ops", true, "");
        NotificationDeliveryEntity receipt = new NotificationDeliveryEntity();
        receipt.setResultJson("not-json");
        given(channels.enabled()).willReturn(List.of(channel));
        given(deliveries.findByIdAndTenantId(any(), eq("tenant-a"))).willReturn(Optional.of(receipt));

        Map<String, Object> result = dispatcher().dispatch(Map.of("id", "AL-CORRUPT"));

        assertEquals(1, result.get("failed"));
        assertTrue(String.valueOf(((Map<?, ?>) ((List<?>) result.get("results")).getFirst()).get("error"))
                .contains("invalid notification delivery receipt"));
        verify(http, never()).postExternal(any(), any(), any(), any(Integer.class));
    }

    @Test
    void dispatchLogFailureDoesNotTurnSuccessfulDeliveryIntoFailure() {
        TenantContext.set("tenant-a");
        Channel channel = new Channel("CH-LOG-FAIL", "Ops", "LOG", "local", true, "");
        given(channels.enabled()).willReturn(List.of(channel));
        given(deliveries.findByIdAndTenantId(any(), eq("tenant-a"))).willReturn(Optional.empty());
        doThrow(new IllegalStateException("database unavailable")).when(dispatchLogs).save(any());

        Map<String, Object> result = dispatcher().dispatch(Map.of("id", "AL-LOG-FAIL"));

        assertEquals(0, result.get("failed"));
        verify(deliveries).save(any());
        verify(dispatchLogs).save(any());
    }

    @Test
    void logMapsValidEntriesAndKeepsCorruptEntriesVisible() {
        TenantContext.set("tenant-a");
        NotificationDispatchLogEntity valid = logEntity("AL-1", "Ops", "WEBHOOK", "sent",
                "{\"detail\":\"accepted\"}");
        NotificationDispatchLogEntity corrupt = logEntity("AL-2", "Mail", "EMAIL", "failed", "broken");
        given(dispatchLogs.findTop200ByTenantIdOrderByCreatedAtDesc("tenant-a"))
                .willReturn(List.of(valid, corrupt));

        List<Map<String, Object>> result = dispatcher().log();

        assertEquals(2, result.size());
        assertEquals("accepted", result.get(0).get("detail"));
        assertEquals("Ops", result.get(0).get("channel"));
        assertEquals("invalid persisted dispatch log", result.get(1).get("error"));
        assertEquals("AL-2", result.get(1).get("alarmId"));
    }

    private static ServiceCall ok() {
        return new ServiceCall(SocpService.NOTIFY, "http://ops", true,
                200, "ok", null, 1, false, 1);
    }

    private NotificationDispatcher dispatcher() {
        return dispatcher(null);
    }

    private NotificationDispatcher dispatcher(SmtpNotificationSender sender) {
        return new NotificationDispatcher(channels, http, deliveries, dispatchLogs, sender);
    }

    private static NotificationDispatchLogEntity logEntity(String alarmId, String channelName,
                                                            String channelType, String status,
                                                            String resultJson) {
        NotificationDispatchLogEntity entity = new NotificationDispatchLogEntity();
        entity.setId(UUID.randomUUID().toString());
        entity.setTenantId("tenant-a");
        entity.setAlarmId(alarmId);
        entity.setChannelName(channelName);
        entity.setChannelType(channelType);
        entity.setStatus(status);
        entity.setResultJson(resultJson);
        entity.setCreatedAt(Instant.now());
        return entity;
    }
}
