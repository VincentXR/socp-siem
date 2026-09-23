package com.socp.notify.web.service;

import com.socp.notify.web.domain.Channel;
import com.socp.notify.web.persistence.entity.NotificationDispatchLogEntity;
import com.socp.notify.web.persistence.store.ChannelStore;
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
    @Mock private NotificationDeliveryState deliveries;
    @Mock private NotificationDispatchLogRepository dispatchLogs;
    @Mock private SmtpNotificationSender smtpSender;

    private final NotificationExecutor executor = new NotificationExecutor();

    @org.junit.jupiter.api.BeforeEach void defaults() {
        org.mockito.Mockito.lenient().when(deliveries.claim(any(), any()))
                .thenReturn(new NotificationDeliveryState.Claim("token", null));
        org.mockito.Mockito.lenient().when(deliveries.finish(any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean()))
                .thenReturn(true);
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
        executor.close();
    }

    @Test
    void selectedChannelTestDoesNotCreateAlarmDeliveryReceiptsOrFanOut() {
        TenantContext.set("tenant-a");
        Channel selected = new Channel("CH-TEST", "Selected", "LOG", "local", false, "");
        assertEquals("logged", dispatcher().test(selected).get("status"));
        verify(channels, never()).enabled();
        verify(deliveries, never()).finish(any(), any(), any(), any(), eq(true));
        ArgumentCaptor<NotificationDispatchLogEntity> record = ArgumentCaptor.forClass(NotificationDispatchLogEntity.class);
        verify(dispatchLogs).save(record.capture());
        assertEquals("tenant-a", record.getValue().getTenantId());
    }

    @Test @org.junit.jupiter.api.Timeout(5)
    void interruptedOperatorTestDoesNotSuggestRetryingAnIdempotentReceipt() throws Exception {
        TenantContext.set("tenant-a");
        Channel selected = new Channel("test", "Selected", "WEBHOOK", "http://fixture.invalid", false, "");
        var started = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        given(http.postExternalOnce(any(), any(), any(), org.mockito.ArgumentMatchers.anyInt())).willAnswer(call -> {
            started.countDown();
            assertTrue(release.await(3, java.util.concurrent.TimeUnit.SECONDS));
            return ok();
        });
        Thread.currentThread().interrupt();
        try {
            assertEquals("NOTIFY_TEST_UNCONFIRMED", dispatcher().test(selected).get("errorCode"));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally { Thread.interrupted(); }
        try { assertTrue(started.await(2, java.util.concurrent.TimeUnit.SECONDS)); }
        finally { release.countDown(); }
        verify(dispatchLogs, org.mockito.Mockito.timeout(1000)).save(any());
        verify(deliveries, never()).claim(any(), any());
    }

    @Test
    void successfulChannelIsPersistedAsAnIdempotencyReceipt() {
        TenantContext.set("tenant-a");
        Channel channel = new Channel("CH-1", "Ops", "WEBHOOK", "http://ops", true, "");
        given(channels.enabled()).willReturn(List.of(channel));
        given(http.postExternalOnce(eq("http://ops"), any(), eq(SocpHttpClient.JSON), eq(3000)))
                .willReturn(ok());
        NotificationDispatcher dispatcher = dispatcher();

        Map<String, Object> result = dispatcher.dispatch(Map.of("id", "AL-1", "severity", "HIGH"));

        assertEquals(0, result.get("failed"));
        verify(deliveries).claim("AL-1", "CH-1");
        verify(deliveries).finish(eq("AL-1"), eq("CH-1"), eq("token"),
                org.mockito.ArgumentMatchers.contains("\"status\":\"sent\""), eq(true));
    }

    @Test
    void logChannelRecordsDeliveryWithoutInvokingExternalConnector() {
        TenantContext.set("tenant-a");
        Channel channel = new Channel("CH-LOG", "Local evidence", "LOG", "golden-demo", true, "");
        given(channels.enabled()).willReturn(List.of(channel));
        NotificationDispatcher dispatcher = dispatcher();

        Map<String, Object> result = dispatcher.dispatch(Map.of("id", "AL-1"));

        assertEquals(0, result.get("failed"));
        List<?> channelResults = (List<?>) result.get("results");
        assertEquals("logged", ((Map<?, ?>) channelResults.getFirst()).get("status"));
        verify(http, never()).postExternalOnce(any(), any(), any(), any(Integer.class));
        verify(deliveries).finish(any(), any(), eq("token"), any(), eq(true));
    }

    @Test
    void replayUsesReceiptAndDoesNotSendChannelAgain() {
        TenantContext.set("tenant-a");
        Channel channel = new Channel("CH-1", "Ops", "WEBHOOK", "http://ops", true, "");
        given(channels.enabled()).willReturn(List.of(channel));
        given(deliveries.claim(any(), any())).willReturn(new NotificationDeliveryState.Claim(null, "{\"channel\":\"Ops\",\"status\":\"sent\"}"));
        NotificationDispatcher dispatcher = dispatcher();

        Map<String, Object> result = dispatcher.dispatch(Map.of("id", "AL-1"));

        List<?> channelResults = (List<?>) result.get("results");
        assertTrue((Boolean) ((Map<?, ?>) channelResults.getFirst()).get("duplicate"));
        verify(http, never()).postExternalOnce(any(), any(), any(), any(Integer.class));
    }

    @Test
    void failedChannelIsNotReceiptedSoAlertDeliveryCanRetryIt() {
        TenantContext.set("tenant-a");
        Channel channel = new Channel("CH-1", "Ops", "WEBHOOK", "http://ops", true, "");
        given(channels.enabled()).willReturn(List.of(channel));
        given(http.postExternalOnce(eq("http://ops"), any(), eq(SocpHttpClient.JSON), eq(3000)))
                .willReturn(new ServiceCall(SocpService.NOTIFY, "http://ops", false,
                        503, "", "unavailable", 1, true, 1));
        NotificationDispatcher dispatcher = dispatcher();

        Map<String, Object> result = dispatcher.dispatch(Map.of("id", "AL-1"));

        assertEquals(1, result.get("failed"));
        verify(deliveries, never()).finish(any(), any(), any(), any(), eq(true));
    }

    @Test
    void unsupportedEmailChannelIsFailedAndNeverReceiptedAsDelivered() {
        TenantContext.set("tenant-a");
        Channel channel = new Channel("CH-EMAIL", "Mail", "EMAIL", "soc@example.com", true, "");
        given(channels.enabled()).willReturn(List.of(channel));
        NotificationDispatcher dispatcher = dispatcher();

        Map<String, Object> result = dispatcher.dispatch(Map.of("id", "AL-1"));

        assertEquals(1, result.get("failed"));
        List<?> results = (List<?>) result.get("results");
        assertEquals("failed", ((Map<?, ?>) results.getFirst()).get("status"));
        verify(deliveries, never()).finish(any(), any(), any(), any(), eq(true));
        verify(http, never()).postExternalOnce(any(), any(), any(), any(Integer.class));
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
        verify(deliveries, never()).claim(any(), any());
    }

    @Test
    void nullHttpResponseIsReportedAsFailedAndCanBeRetried() {
        TenantContext.set("tenant-a");
        Channel channel = new Channel("CH-NULL", "Ops", "WEBHOOK", "http://ops", true, "");
        given(channels.enabled()).willReturn(List.of(channel));
        given(http.postExternalOnce(eq("http://ops"), any(), eq(SocpHttpClient.JSON), eq(3000)))
                .willReturn(null);

        Map<String, Object> result = dispatcher().dispatch(Map.of("id", "AL-NULL"));

        assertEquals(1, result.get("failed"));
        Map<?, ?> channelResult = (Map<?, ?>) ((List<?>) result.get("results")).getFirst();
        assertEquals("failed", channelResult.get("status"));
        assertEquals(0, channelResult.get("httpStatus"));
        verify(deliveries, never()).finish(any(), any(), any(), any(), eq(true));
    }

    @Test
    void connectorExceptionIsContainedToTheChannelResult() {
        TenantContext.set("tenant-a");
        Channel channel = new Channel("CH-ERR", "Ops", "WEBHOOK", "http://ops", true, "");
        given(channels.enabled()).willReturn(List.of(channel));
        given(http.postExternalOnce(eq("http://ops"), any(), eq(SocpHttpClient.JSON), eq(3000)))
                .willThrow(new IllegalStateException("connector down"));

        Map<String, Object> result = dispatcher().dispatch(Map.of("id", "AL-ERR"));

        assertEquals(1, result.get("failed"));
        Map<?, ?> channelResult = (Map<?, ?>) ((List<?>) result.get("results")).getFirst();
        assertEquals("failed", channelResult.get("status"));
        assertEquals("NOTIFY_CONNECTOR_FAILED", channelResult.get("errorCode"));
        verify(deliveries, never()).finish(any(), any(), any(), any(), eq(true));
    }

    @Test
    void instantMessageChannelsUseStructuredTextPayload() {
        TenantContext.set("tenant-a");
        Channel channel = new Channel("CH-SLACK", "Slack", "SLACK", "http://slack", true, "");
        given(channels.enabled()).willReturn(List.of(channel));
        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        given(http.postExternalOnce(eq("http://slack"), payload.capture(), eq(SocpHttpClient.JSON), eq(3000)))
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
        String longBody = "x".repeat(400);
        given(http.postExternalOnce(eq("http://pager"), any(), eq(SocpHttpClient.JSON), eq(3000)))
                .willReturn(new ServiceCall(SocpService.NOTIFY, "http://pager", false,
                        500, longBody, "remote failure", 1, true, 1));

        Map<String, Object> result = dispatcher().dispatch(Map.of("id", "AL-PAGER"));

        Map<?, ?> channelResult = (Map<?, ?>) ((List<?>) result.get("results")).getFirst();
        assertEquals("failed", channelResult.get("status"));
        assertTrue(String.valueOf(channelResult.get("detail")).endsWith("..."));
        verify(http).postExternalOnce(eq("http://pager"),
                org.mockito.ArgumentMatchers.argThat(body -> body.contains("\"alarm\"")),
                eq(SocpHttpClient.JSON), eq(3000));
    }

    @Test
    void smtpSuccessIsReceiptedAndReceivesRenderedAlarmText() {
        TenantContext.set("tenant-a");
        Channel channel = new Channel("CH-MAIL", "Mail", "EMAIL", " soc@example.com ", true, "");
        given(channels.enabled()).willReturn(List.of(channel));
        given(smtpSender.send(eq(" soc@example.com "), eq("SOCP security alarm: AL-MAIL"), any()))
                .willReturn(new SmtpNotificationSender.DeliveryResult(true, null, "accepted"));

        Map<String, Object> result = dispatcher(smtpSender).dispatch(Map.of(
                "id", "AL-MAIL", "severity", "HIGH", "message", "suspicious"));

        assertEquals(0, result.get("failed"));
        assertEquals("sent", ((Map<?, ?>) ((List<?>) result.get("results")).getFirst()).get("status"));
        verify(deliveries).finish(any(), any(), eq("token"), any(), eq(true));
        verify(smtpSender).send(eq(" soc@example.com "), eq("SOCP security alarm: AL-MAIL"),
                org.mockito.ArgumentMatchers.argThat(text -> text.contains("suspicious")));
    }

    @Test
    void smtpFailureIncludesConnectorErrorAndIsNotReceipted() {
        TenantContext.set("tenant-a");
        Channel channel = new Channel("CH-MAIL", "Mail", "EMAIL", "soc@example.com", true, "");
        given(channels.enabled()).willReturn(List.of(channel));
        given(smtpSender.send(any(), any(), any()))
                .willReturn(new SmtpNotificationSender.DeliveryResult(false, "SMTP_SEND_FAILED", "rejected"));

        Map<String, Object> result = dispatcher(smtpSender).dispatch(Map.of("id", "AL-MAIL-FAIL"));

        Map<?, ?> channelResult = (Map<?, ?>) ((List<?>) result.get("results")).getFirst();
        assertEquals(1, result.get("failed"));
        assertEquals("SMTP_SEND_FAILED", channelResult.get("errorCode"));
        verify(deliveries, never()).finish(any(), any(), any(), any(), eq(true));
    }

    @Test
    void corruptReceiptIsReturnedAsFailedInsteadOfSendingAgain() {
        TenantContext.set("tenant-a");
        Channel channel = new Channel("CH-CORRUPT", "Ops", "WEBHOOK", "http://ops", true, "");
        given(channels.enabled()).willReturn(List.of(channel));
        given(deliveries.claim(any(), any())).willReturn(new NotificationDeliveryState.Claim(null, "not-json"));

        Map<String, Object> result = dispatcher().dispatch(Map.of("id", "AL-CORRUPT"));

        assertEquals(1, result.get("failed"));
        assertTrue(String.valueOf(((Map<?, ?>) ((List<?>) result.get("results")).getFirst()).get("errorCode"))
                .contains("NOTIFY_RECEIPT_INVALID"));
        verify(http, never()).postExternalOnce(any(), any(), any(), any(Integer.class));
    }

    @Test
    void activeClaimDoesNotSendOrAcknowledgeAnotherWorkersDelivery() {
        TenantContext.set("tenant-a");
        Channel channel = new Channel("busy", "Busy", "LOG", "local", true, "");
        given(channels.enabled()).willReturn(List.of(channel));
        given(deliveries.claim(any(), any())).willReturn(new NotificationDeliveryState.Claim(null, null));
        var response = dispatcher().dispatch(Map.of("id", "alarm"));
        assertEquals(1, response.get("failed"));
        assertEquals("NOTIFY_PENDING", ((Map<?, ?>) ((List<?>) response.get("results")).getFirst()).get("errorCode"));
        verify(deliveries, never()).finish(any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean());
        verify(dispatchLogs, never()).save(any());
    }

    @Test
    void receiptFailureAndLostClaimDoNotReportSuccessfulDelivery() {
        TenantContext.set("tenant-a");
        Channel channel = new Channel("one", "Ops", "LOG", "local", true, "");
        given(channels.enabled()).willReturn(List.of(channel));
        given(deliveries.finish(any(), any(), any(), any(), eq(true)))
                .willThrow(new IllegalStateException("private database information"))
                .willReturn(false);
        var first = dispatcher().dispatch(Map.of("id", "alarm"));
        assertEquals(1, first.get("failed"));
        assertEquals("NOTIFY_RECEIPT_UNCONFIRMED", ((Map<?, ?>) ((List<?>) first.get("results")).getFirst()).get("errorCode"));
        var second = dispatcher().dispatch(Map.of("id", "alarm"));
        assertEquals(1, second.get("failed"));
        assertEquals("NOTIFY_CLAIM_LOST", ((Map<?, ?>) ((List<?>) second.get("results")).getFirst()).get("errorCode"));
    }

    @Test @org.junit.jupiter.api.Timeout(10)
    void responseWaitEndsWhileAdmittedIoCanStillPersistItsReceipt() throws Exception {
        TenantContext.set("tenant-a");
        Channel channel = new Channel("slow", "Slow", "WEBHOOK", "http://fixture.invalid", true, "");
        given(channels.enabled()).willReturn(List.of(channel));
        var release = new java.util.concurrent.CountDownLatch(1);
        var finished = new java.util.concurrent.CountDownLatch(1);
        given(http.postExternalOnce(any(), any(), any(), org.mockito.ArgumentMatchers.anyInt())).willAnswer(call -> {
            assertTrue(release.await(8, java.util.concurrent.TimeUnit.SECONDS));
            return ok();
        });
        given(deliveries.finish(any(), any(), any(), any(), eq(true))).willAnswer(call -> { finished.countDown(); return true; });
        try {
            var response = dispatcher().dispatch(Map.of("id", "alarm"));
            assertEquals(1, response.get("failed"));
            assertEquals("NOTIFY_PENDING", ((Map<?, ?>) ((List<?>) response.get("results")).getFirst()).get("errorCode"));
            verify(deliveries, never()).finish(any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean());
        } finally { release.countDown(); }
        assertTrue(finished.await(2, java.util.concurrent.TimeUnit.SECONDS));
    }

    @Test
    void oversizedPayloadIsRejectedBeforeChannelLookup() {
        assertEquals(413, assertThrows(com.socp.platform.error.exception.ApiException.class,
                () -> dispatcher().dispatch(Map.of("id", "alarm", "extra", "x".repeat(256 * 1024)))).getCode());
        verify(channels, never()).enabled();
    }

    @Test
    void dispatchLogFailureDoesNotTurnSuccessfulDeliveryIntoFailure() {
        TenantContext.set("tenant-a");
        Channel channel = new Channel("CH-LOG-FAIL", "Ops", "LOG", "local", true, "");
        given(channels.enabled()).willReturn(List.of(channel));
        doThrow(new IllegalStateException("database unavailable")).when(dispatchLogs).save(any());

        Map<String, Object> result = dispatcher().dispatch(Map.of("id", "AL-LOG-FAIL"));

        assertEquals(0, result.get("failed"));
        verify(deliveries).finish(any(), any(), eq("token"), any(), eq(true));
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
        return new NotificationDispatcher(channels, http, deliveries, dispatchLogs, sender, executor);
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
