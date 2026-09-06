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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/** Verifies how AlertClient maps typed calls onto the raw HTTP client. */
@ExtendWith(MockitoExtension.class)
class AlertClientCoverageTest {

    @Mock
    private SocpHttpClient http;

    private AlertClient client;

    @BeforeEach
    void setUp() {
        client = new AlertClient(http);
    }

    private ServiceCall ok() {
        return new ServiceCall(SocpService.ALERT, "http://alert-web/api", true, 200, "{}", null, 5, false, 1);
    }

    @Test
    void forwardAlarmPostsToTheAlarmIngestPath() {
        given(http.postJson(SocpService.ALERT, "/api/alarms", "{\"id\":1}")).willReturn(ok());

        ServiceCall call = client.forwardAlarm("{\"id\":1}");

        assertThat(call.ok()).isTrue();
        verify(http).postJson(SocpService.ALERT, "/api/alarms", "{\"id\":1}");
    }

    @Test
    void statsUsesTheAggregatePathAndAddsTheWindowParameter() {
        given(http.get(SocpService.ALERT, "/api/alarms/stats")).willReturn(ok());
        given(http.get(SocpService.ALERT, "/api/alarms/stats?window=7d")).willReturn(ok());

        assertThat(client.stats().ok()).isTrue();
        assertThat(client.stats("7d").ok()).isTrue();
        assertThat(client.stats("  ").ok()).isTrue();

        verify(http).get(SocpService.ALERT, "/api/alarms/stats?window=7d");
        verify(http, org.mockito.Mockito.times(2)).get(SocpService.ALERT, "/api/alarms/stats");
    }

    @Test
    void alarmLookupsUrlEncodeTheIdentifier() {
        given(http.get(SocpService.ALERT, "/api/alarms/alarm%2F1%20x")).willReturn(ok());
        given(http.get(SocpService.ALERT, "/api/alarms/alarm%2F1%20x/evidence")).willReturn(ok());

        assertThat(client.getAlarm("alarm/1 x").ok()).isTrue();
        assertThat(client.evidence("alarm/1 x").ok()).isTrue();

        verify(http).get(SocpService.ALERT, "/api/alarms/alarm%2F1%20x");
        verify(http).get(SocpService.ALERT, "/api/alarms/alarm%2F1%20x/evidence");
    }

    @Test
    void addNoteBodyEscapesJsonAndCarriesTheIdempotencyHeader() {
        given(http.postJson(eq(SocpService.ALERT), eq("/api/alarms/a1/notes"),
                eq("{\"author\":\"alice\",\"content\":\"say \\\"hi\\\"\\nline\"}"),
                eq(Map.of("Idempotency-Key", "key-9")))).willReturn(ok());

        ServiceCall call = client.addNote("a1", "alice", "say \"hi\"\nline", "key-9");

        assertThat(call.ok()).isTrue();
        verify(http).postJson(SocpService.ALERT, "/api/alarms/a1/notes",
                "{\"author\":\"alice\",\"content\":\"say \\\"hi\\\"\\nline\"}",
                Map.of("Idempotency-Key", "key-9"));
    }

    @Test
    void addNoteWithoutKeyOmitsTheHeaderAndNullAuthorBecomesJsonNull() {
        given(http.postJson(SocpService.ALERT, "/api/alarms/a1/notes",
                "{\"author\":null,\"content\":\"note\"}", Map.of())).willReturn(ok());

        assertThat(client.addNote("a1", null, "note").ok()).isTrue();

        verify(http).postJson(SocpService.ALERT, "/api/alarms/a1/notes",
                "{\"author\":null,\"content\":\"note\"}", Map.of());
    }

    @Test
    void blankIdempotencyKeyIsTreatedAsAbsent() {
        given(http.postJson(SocpService.ALERT, "/api/alarms/a1/tags",
                "{\"tag\":\"prod\"}", Map.of())).willReturn(ok());

        assertThat(client.addTag("a1", "prod", "  ").ok()).isTrue();
    }

    @Test
    void assignAndSetStatusMapToPostAndPut() {
        given(http.postJson(SocpService.ALERT, "/api/alarms/a1/assign",
                "{\"assignee\":\"bob\"}", Map.of("Idempotency-Key", "k"))).willReturn(ok());
        given(http.putJson(SocpService.ALERT, "/api/alarms/a1/status",
                "{\"status\":\"RESOLVED\"}", Map.of())).willReturn(ok());

        assertThat(client.assign("a1", "bob", "k").ok()).isTrue();
        assertThat(client.setStatus("a1", "RESOLVED").ok()).isTrue();

        verify(http).postJson(SocpService.ALERT, "/api/alarms/a1/assign",
                "{\"assignee\":\"bob\"}", Map.of("Idempotency-Key", "k"));
        verify(http).putJson(SocpService.ALERT, "/api/alarms/a1/status",
                "{\"status\":\"RESOLVED\"}", Map.of());
    }
}
