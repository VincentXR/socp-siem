package com.socp.platform.client.service;

import com.socp.platform.client.http.ServiceCall;
import com.socp.platform.client.http.SocpHttpClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/** Verifies how NotifyClient maps typed calls onto the raw HTTP client. */
@ExtendWith(MockitoExtension.class)
class NotifyClientCoverageTest {

    @Mock
    private SocpHttpClient http;

    private NotifyClient client;

    @BeforeEach
    void setUp() {
        client = new NotifyClient(http);
    }

    @Test
    void notifyAlertPostsToTheAlertNotifyPath() {
        ServiceCall call = new ServiceCall(SocpService.NOTIFY, "http://notify-web/api", true, 200,
                "{}", null, 5, false, 1);
        given(http.postJson(SocpService.NOTIFY, "/api/v1/notify/alert", "{\"alarmId\":1}"))
                .willReturn(call);

        assertThat(client.notifyAlert("{\"alarmId\":1}").ok()).isTrue();
        verify(http).postJson(SocpService.NOTIFY, "/api/v1/notify/alert", "{\"alarmId\":1}");
    }

    @Test
    void notifyAlertForwardsTheIdempotencyKeyOnlyWhenPresent() {
        ServiceCall call = new ServiceCall(SocpService.NOTIFY, "http://notify-web/api", true, 200,
                "{}", null, 5, false, 1);
        given(http.postJson(SocpService.NOTIFY, "/api/v1/notify/alert", "{}",
                Map.of("Idempotency-Key", "run-9"))).willReturn(call);
        given(http.postJson(SocpService.NOTIFY, "/api/v1/notify/alert", "{}", Map.of()))
                .willReturn(call);

        assertThat(client.notifyAlert("{}", "run-9").ok()).isTrue();
        assertThat(client.notifyAlert("{}", null).ok()).isTrue();

        verify(http).postJson(SocpService.NOTIFY, "/api/v1/notify/alert", "{}",
                Map.of("Idempotency-Key", "run-9"));
        verify(http).postJson(SocpService.NOTIFY, "/api/v1/notify/alert", "{}", Map.of());
    }
}
