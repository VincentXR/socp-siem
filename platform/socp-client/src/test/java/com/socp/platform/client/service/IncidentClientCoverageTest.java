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

/** Verifies how IncidentClient maps typed calls onto the raw HTTP client. */
@ExtendWith(MockitoExtension.class)
class IncidentClientCoverageTest {

    @Mock
    private SocpHttpClient http;

    private IncidentClient client;

    @BeforeEach
    void setUp() {
        client = new IncidentClient(http);
    }

    private ServiceCall ok() {
        return new ServiceCall(SocpService.INCIDENT, "http://incident-web/api", true, 200, "{}", null, 5, false, 1);
    }

    @Test
    void createFromAlarmPostsToTheFromAlarmPath() {
        given(http.postJson(SocpService.INCIDENT, "/api/v1/incidents/from-alarm", "{\"alarmId\":1}"))
                .willReturn(ok());

        assertThat(client.createFromAlarm("{\"alarmId\":1}").ok()).isTrue();
    }

    @Test
    void createFromAlarmForwardsTheIdempotencyKeyOnlyWhenPresent() {
        given(http.postJson(SocpService.INCIDENT, "/api/v1/incidents/from-alarm", "{}",
                Map.of("Idempotency-Key", "run-1"))).willReturn(ok());
        given(http.postJson(SocpService.INCIDENT, "/api/v1/incidents/from-alarm", "{}", Map.of()))
                .willReturn(ok());

        assertThat(client.createFromAlarm("{}", "run-1").ok()).isTrue();
        assertThat(client.createFromAlarm("{}", "  ").ok()).isTrue();

        verify(http).postJson(SocpService.INCIDENT, "/api/v1/incidents/from-alarm", "{}",
                Map.of("Idempotency-Key", "run-1"));
        verify(http).postJson(SocpService.INCIDENT, "/api/v1/incidents/from-alarm", "{}", Map.of());
    }

    @Test
    void listUsesTheIncidentCollectionPath() {
        given(http.get(SocpService.INCIDENT, "/api/v1/incidents")).willReturn(ok());

        assertThat(client.list().ok()).isTrue();
    }

    @Test
    void addNoteEncodesAuthorContentAndOptionalKeyIntoTheQuery() {
        given(http.postJson(SocpService.INCIDENT,
                "/api/v1/incidents/c%201/notes?author=alice&content=hello%20there&idempotencyKey=key-1",
                "{}")).willReturn(ok());

        ServiceCall call = client.addNote("c 1", "alice", "hello there", "key-1");

        assertThat(call.ok()).isTrue();
        verify(http).postJson(SocpService.INCIDENT,
                "/api/v1/incidents/c%201/notes?author=alice&content=hello%20there&idempotencyKey=key-1", "{}");
    }

    @Test
    void addNoteWithoutKeyOmitsTheIdempotencyParameter() {
        given(http.postJson(SocpService.INCIDENT,
                "/api/v1/incidents/c1/notes?author=alice&content=hello", "{}")).willReturn(ok());

        assertThat(client.addNote("c1", "alice", "hello").ok()).isTrue();
    }

    @Test
    void setStatusEncodesStatusAndSkipsBlankAssignee() {
        given(http.postJson(SocpService.INCIDENT,
                "/api/v1/incidents/c1/status?status=CLOSED&assignee=bob", "{}")).willReturn(ok());
        given(http.postJson(SocpService.INCIDENT,
                "/api/v1/incidents/c1/status?status=CLOSED", "{}")).willReturn(ok());

        assertThat(client.setStatus("c1", "CLOSED", "bob").ok()).isTrue();
        assertThat(client.setStatus("c1", "CLOSED", "  ").ok()).isTrue();

        verify(http).postJson(SocpService.INCIDENT,
                "/api/v1/incidents/c1/status?status=CLOSED&assignee=bob", "{}");
        verify(http).postJson(SocpService.INCIDENT,
                "/api/v1/incidents/c1/status?status=CLOSED", "{}");
    }
}
