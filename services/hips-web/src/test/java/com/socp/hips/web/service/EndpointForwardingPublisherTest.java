package com.socp.hips.web.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.socp.hips.web.persistence.store.EndpointForwardingStore;
import com.socp.platform.client.http.ServiceCall;
import com.socp.platform.client.http.SocpHttpClient;
import com.socp.platform.client.service.SocpService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.assertj.core.api.Assertions.assertThat;

class EndpointForwardingPublisherTest {
    private final EndpointForwardingStore store = mock(EndpointForwardingStore.class);
    private final SocpHttpClient http = mock(SocpHttpClient.class);
    private final EndpointForwardingPublisher publisher = new EndpointForwardingPublisher(store, http, new ObjectMapper(), true);

    @Test
    void backgroundPassAdmitsAtMostFiveClaimsAndCanBePaused() {
        var claim = new EndpointForwardingStore.Claim("event-1", "tenant-a", "{}", "token", 1);
        given(store.claim(isNull(), isNull(), any())).willReturn(Optional.of(claim));
        given(http.post(any(), anyString(), anyString(), anyString(), anyInt(), anyMap()))
                .willReturn(new ServiceCall(SocpService.SEARCH, "http://search", false, 503, "", null, 1, false, 1));
        publisher.drain();
        verify(store, times(5)).claim(isNull(), isNull(), any());
        verify(http, times(5)).post(any(), anyString(), anyString(), anyString(), anyInt(), anyMap());
        verify(store).prune(any());
        clearInvocations(store, http);
        new EndpointForwardingPublisher(store, http, new ObjectMapper(), false).drain();
        verifyNoInteractions(store, http);
    }

    @Test
    void unexpectedTransportFailureIsRecordedAndRestoresTenant() {
        var claim = new EndpointForwardingStore.Claim("event-1", "tenant-a", "{}", "token", 1);
        given(store.claim(eq("tenant-a"), eq("event-1"), any())).willReturn(Optional.of(claim));
        given(http.post(any(), anyString(), anyString(), anyString(), anyInt(), anyMap()))
                .willThrow(new IllegalStateException("sensitive connector details"));
        com.socp.platform.tenant.context.TenantContext.runWith("caller", () -> {
            assertThat(publisher.forward("tenant-a", "event-1")).isFalse();
            assertThat(com.socp.platform.tenant.context.TenantContext.require()).isEqualTo("caller");
        });
        verify(store).fail(eq(claim), any(), eq("Transport failure"));
        verify(store, never()).complete(any(), any());
    }

    @Test
    void unownedRowsDoNotSendAndOnlyDeliveredReceiptsReturnSuccess() {
        given(store.claim(anyString(), anyString(), any())).willReturn(Optional.empty());
        given(store.status("tenant-a", "event-1")).willReturn("PROCESSING", "DELIVERED", "DEAD", "UNKNOWN");
        assertThat(publisher.forward("tenant-a", "event-1")).isFalse();
        assertThat(publisher.forward("tenant-a", "event-1")).isTrue();
        assertThat(publisher.forward("tenant-a", "event-1")).isFalse();
        assertThat(publisher.forward("tenant-a", "event-1")).isFalse();
        verifyNoInteractions(http);
    }
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {
            "accepted", "null", "{}", "[]", "   ",
            "{\"code\":0,\"data\":{\"accepted\":1,\"acknowledged\":1,\"skipped\":0}} {}",
            "{\"code\":0,\"data\":{\"accepted\":0,\"acknowledged\":0,\"skipped\":1}}",
            "{\"code\":500,\"data\":{\"accepted\":1,\"acknowledged\":1,\"skipped\":0}}",
            "{\"data\":{\"accepted\":1,\"acknowledged\":1,\"skipped\":0}}",
            "{\"code\":0,\"data\":{\"accepted\":1,\"skipped\":0}}",
            "{\"code\":0,\"data\":{\"accepted\":1,\"acknowledged\":1}}",
            "{\"code\":0,\"data\":{\"accepted\":1,\"acknowledged\":1,\"skipped\":1}}",
            "{\"code\":0,\"data\":{\"accepted\":2,\"acknowledged\":2,\"skipped\":0}}",
            "{\"code\":0,\"data\":{\"accepted\":1,\"acknowledged\":\"1\",\"skipped\":0}}",
            "{\"code\":0,\"data\":{\"accepted\":1,\"acknowledged\":1.5,\"skipped\":0}}",
            "{\"code\":0,\"data\":{\"accepted\":1,\"acknowledged\":4294967297,\"skipped\":0}}"
    })
    void httpSuccessWithoutSingleEventAcknowledgementDoesNotClaimForwarding(String body) throws Exception {
        var result = reportWithResponse(true, 200, body);
        assertThat(result).isFalse();
    }

    @Test
    void duplicateAcknowledgementCountsAsForwarded() throws Exception {
        var result = reportWithResponse(true, 200,
                "{\"code\":0,\"data\":{\"accepted\":1,\"acknowledged\":1,\"created\":0,\"duplicates\":1,\"skipped\":0}}");
        assertThat(result).isTrue();
    }

    @Test
    void failedTransportCannotAcknowledgeEvenWithSuccessBody() throws Exception {
        var result = reportWithResponse(false, 503,
                "{\"code\":0,\"data\":{\"accepted\":1,\"acknowledged\":1,\"skipped\":0}}");
        assertThat(result).isFalse();
    }

    private boolean reportWithResponse(boolean ok, int status, String body) {
        var claim = new EndpointForwardingStore.Claim("event-1", "tenant-a", "{}", "token", 1);
        given(store.claim(eq("tenant-a"), eq("event-1"), any())).willReturn(Optional.of(claim));
        given(store.complete(eq(claim), any())).willReturn(true);
        given(http.post(eq(SocpService.SEARCH), eq("/api/v1/ingest"), anyString(),
                eq(SocpHttpClient.NDJSON), eq(5000), eq(Map.of("Idempotency-Key", "hips:event-1"))))
                .willAnswer(call -> {
                    assertThat(com.socp.platform.tenant.context.TenantContext.require()).isEqualTo("tenant-a");
                    return new ServiceCall(SocpService.SEARCH, "http://search", ok, status, body, null, 1, false, 1);
                });
        boolean forwarded = publisher.forward("tenant-a", "event-1");
        if (forwarded) verify(store).complete(eq(claim), any());
        else verify(store).fail(eq(claim), any(), anyString());
        return forwarded;
    }
}
