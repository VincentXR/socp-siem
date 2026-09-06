package com.socp.soar.web.connector;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.socp.platform.client.http.ServiceCall;
import com.socp.platform.client.http.SocpHttpClient;
import com.socp.platform.client.service.AlertClient;
import com.socp.platform.client.service.IncidentClient;
import com.socp.platform.client.service.NotifyClient;
import com.socp.platform.client.service.SearchClient;
import com.socp.platform.client.service.SocpService;
import com.socp.platform.client.service.ThreatClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/** Branch coverage for built-in connector dispatch, egress, and error mapping. */
@ExtendWith(MockitoExtension.class)
class SoarConnectorRegistryBranchCoverageTest {

    @Mock
    private AlertClient alert;
    @Mock
    private IncidentClient incident;
    @Mock
    private NotifyClient notify;
    @Mock
    private SearchClient search;
    @Mock
    private ThreatClient threat;
    @Mock
    private SocpHttpClient http;
    @Mock
    private EnvironmentSecretResolver secrets;

    private final ObjectMapper mapper = new ObjectMapper();
    private SoarConnectorRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new SoarConnectorRegistry(alert, incident, notify, search, threat, http,
                secrets, mapper);
    }

    @Test
    void alertWriteActionsDispatchToClientWithIdempotencyKey() {
        given(alert.addNote(eq("a1"), eq("soar"), eq("check host"), eq("idem-1")))
                .willReturn(ok("{\"operationId\":\"op-1\"}"));
        given(alert.assign(eq("a1"), eq("alice"), eq("idem-1")))
                .willReturn(ok("{\"operationId\":\"op-2\"}"));
        given(alert.setStatus(eq("a1"), eq("RESOLVED"), eq("idem-1")))
                .willReturn(ok("{\"operationId\":\"op-3\"}"));

        ActionResult note = registry.execute(request("socp.alert/add-note",
                "alertId", "a1", "content", "check host"));
        ActionResult assigned = registry.execute(request("socp.alert/assign",
                "alertId", "a1", "assignee", "alice"));
        ActionResult status = registry.execute(request("socp.alert/set-status",
                "alertId", "a1", "status", "RESOLVED"));

        assertThat(note.status()).isEqualTo("SUCCEEDED");
        assertThat(note.operationId()).isEqualTo("op-1");
        assertThat(assigned.operationId()).isEqualTo("op-2");
        assertThat(status.operationId()).isEqualTo("op-3");
    }

    @Test
    void incidentActionsCoverTimelineCreateAssignStatusAndTasks() {
        given(incident.addNote(eq("c1"), eq("soar"), eq("timeline entry"), eq("idem-1")))
                .willReturn(ok("{\"operationId\":\"op-t\"}"));
        given(incident.createFromAlarm(anyString(), eq("idem-1")))
                .willReturn(ok("{\"operationId\":\"op-c\"}"));
        given(incident.setStatus(eq("c1"), eq("INVESTIGATING"), eq("alice")))
                .willReturn(ok("{\"operationId\":\"op-a\"}"));
        given(incident.setStatus(eq("c1"), eq("CLOSED"), eq("")))
                .willReturn(ok("{\"operationId\":\"op-s\"}"));
        given(incident.addNote(eq("c1"), eq("soar"), eq("SOAR case task: add-task"), eq("idem-1")))
                .willReturn(ok("{\"operationId\":\"op-1\"}"));
        given(incident.addNote(eq("c1"), eq("soar"), eq("SOAR case task: complete-task"), eq("idem-1")))
                .willReturn(ok("{\"operationId\":\"op-2\"}"));

        assertThat(registry.execute(request("socp.incident/append-timeline",
                "incidentId", "c1", "content", "timeline entry")).operationId()).isEqualTo("op-t");
        assertThat(registry.execute(request("socp.incident/create",
                "title", "case")).operationId()).isEqualTo("op-c");
        assertThat(registry.execute(request("socp.incident/assign",
                "incidentId", "c1", "assignee", "alice")).operationId()).isEqualTo("op-a");
        assertThat(registry.execute(request("socp.incident/set-status",
                "incidentId", "c1", "status", "CLOSED")).operationId()).isEqualTo("op-s");
        assertThat(registry.execute(request("socp.incident/add-task",
                "incidentId", "c1")).operationId()).isEqualTo("op-1");
        assertThat(registry.execute(request("socp.incident/complete-task",
                "incidentId", "c1")).operationId()).isEqualTo("op-2");
    }

    @Test
    void assetSelectorWithoutMatchReturnsEmptyCollection() {
        given(http.get(eq(SocpService.ASSET), eq("/api/v1/assets")))
                .willReturn(ok("[{\"id\":\"a1\",\"name\":\"web\",\"ip\":\"1.2.3.4\","
                        + "\"owner\":\"bob\",\"type\":\"server\",\"os\":\"linux\"}]"));

        ActionResult result = registry.execute(request("socp.asset/find-by-entity",
                "entity", "no-such-host"));

        assertThat(result.status()).isEqualTo("SUCCEEDED");
        assertThat(result.output()).containsEntry("count", 0).containsEntry("matches", List.of());
    }

    @Test
    void notifyChannelWithBlankBodyReportsMissingReceipt() {
        given(notify.notifyAlert(anyString(), eq("idem-1")))
                .willReturn(new ServiceCall(SocpService.NOTIFY, "http://notify", true, 200, null,
                        null, 3, false, 1));

        ActionResult result = registry.execute(request("socp.notify/send-channel"));

        assertThat(result.status()).isEqualTo("FAILED");
        assertThat(result.errorCode()).isEqualTo("MISSING_CONNECTOR_RECEIPT");
        assertThat(result.errorMessage()).isEqualTo("missing response body");
    }

    @Test
    void threatLookupWithEmptyObjectBodyReportsMissingVerifiableReceipt() {
        given(threat.matchIocs(eq("[\"1.2.3.4\"]"))).willReturn(ok("{}"));

        ActionResult result = registry.execute(request("socp.threat-intel/lookup-ioc",
                "ioc", "1.2.3.4"));

        assertThat(result.errorCode()).isEqualTo("MISSING_CONNECTOR_RECEIPT");
        assertThat(result.errorMessage()).contains("verifiable receipt");
    }

    @Test
    void unparseableVersionQualifierFallsBackToLatest() {
        given(alert.getAlarm("a1")).willReturn(ok("{\"operationId\":\"op-9\"}"));

        ActionResult result = registry.execute(request("socp.alert/get@abc", "alertId", "a1"));

        assertThat(result.status()).isEqualTo("SUCCEEDED");
        assertThat(result.operationId()).isEqualTo("op-9");
    }

    @Test
    void malformedSuccessBodyIsWrappedInRawOutput() {
        given(alert.getAlarm("a1")).willReturn(ok("not-json"));

        ActionResult result = registry.execute(request("socp.alert/get", "alertId", "a1"));

        assertThat(result.status()).isEqualTo("SUCCEEDED");
        assertThat(result.operationId()).isNull();
        assertThat(result.output()).containsEntry("raw", "not-json");
    }

    @Test
    void reconcileSwallowsProviderOutageAsAdvisoryEmpty() {
        ActionQuery query = mock(ActionQuery.class);
        given(query.actionRef()).willReturn("socp.alert/get");
        given(query.tenantId()).willThrow(new IllegalStateException("provider outage"));

        assertThat(registry.reconcile(query)).isEmpty();
    }

    @Test
    void compensateSwallowsRequestConstructionFailure() {
        ActionRequest request = mock(ActionRequest.class);
        given(request.tenantId()).willThrow(new IllegalStateException("boom"));

        assertThat(registry.compensate(request, "socp.alert/add-note")).isEmpty();
    }

    @Test
    void webhookTestRejectsBlankEndpointAndSendsBearerToken() {
        ConnectionContext blank = new ConnectionContext("tenant-a", "conn-1", 1, "http.webhook",
                "", Map.of(), Map.of(), secrets, Duration.ofSeconds(5));
        ConnectionTestResult missing = registry.test("http.webhook", blank);
        assertThat(missing.healthy()).isFalse();
        assertThat(missing.errorCode()).isEqualTo("SOAR_CONNECTION_UNAVAILABLE");

        ConnectionContext context = new ConnectionContext("tenant-a", "conn-1", 1, "http.webhook",
                "https://hooks.example.test/x", Map.of(), Map.of("auth", "vault://token"), secrets,
                Duration.ofSeconds(5));
        given(secrets.resolve("vault://token")).willReturn(Optional.of("tok"));
        given(http.postExternal(eq("https://hooks.example.test/x"), eq("{}"), eq(SocpHttpClient.JSON),
                anyInt(), eq(Map.of("Authorization", "Bearer tok")), any()))
                .willReturn(ok("{}"));

        ConnectionTestResult result = registry.test("http.webhook", context);

        assertThat(result.healthy()).isTrue();
    }

    @Test
    void webhookExecuteSerializesParametersAndMapsExternalReceipt() {
        ConnectionContext context = new ConnectionContext("tenant-a", "conn-1", 1, "http.webhook",
                "https://hooks.example.test/x", Map.of(), Map.of(), secrets, Duration.ofSeconds(5));
        given(http.postExternal(eq("https://hooks.example.test/x"), eq("{}"), eq(SocpHttpClient.JSON),
                anyInt(), any(), any()))
                .willReturn(ok("{\"operationId\":\"op-1\"}"));

        ActionResult result = registry.execute(requestWithConnection("http.webhook/request",
                context, "x", new Object()));

        assertThat(result.status()).isEqualTo("SUCCEEDED");
        assertThat(result.operationId()).isEqualTo("op-1");
    }

    @Test
    void legacyDescriptorOverloadsProjectSideEffectFlags() throws Exception {
        Method fourArg = SoarConnectorRegistry.class.getDeclaredMethod("action",
                String.class, String.class, String.class, boolean.class);
        fourArg.setAccessible(true);
        ActionDescriptor reversible = (ActionDescriptor) fourArg.invoke(null, "demo", "Demo", "LOW", true);
        ActionDescriptor readOnly = (ActionDescriptor) fourArg.invoke(null, "demo2", "Demo2", "LOW", false);
        assertThat(reversible.sideEffect()).isEqualTo("REVERSIBLE");
        assertThat(reversible.idempotency()).isEqualTo("NATIVE");
        assertThat(readOnly.sideEffect()).isEqualTo("NONE");
        assertThat(readOnly.idempotency()).isEqualTo("NONE");

        Method fiveArg = SoarConnectorRegistry.class.getDeclaredMethod("action",
                String.class, String.class, String.class, boolean.class, boolean.class);
        fiveArg.setAccessible(true);
        ActionDescriptor requiresConnection = (ActionDescriptor) fiveArg.invoke(null,
                "demo3", "Demo3", "MEDIUM", true, true);
        assertThat(requiresConnection.requiresConnection()).isTrue();
        assertThat(requiresConnection.sideEffect()).isEqualTo("REVERSIBLE");
    }

    // ---- helpers ----

    private static ServiceCall ok(String body) {
        return new ServiceCall(SocpService.ALERT, "http://service", true, 200, body, null, 3, false, 1);
    }

    private static ActionRequest request(String actionRef, Object... keyValue) {
        return requestWithConnection(actionRef, null, keyValue);
    }

    private static ActionRequest requestWithConnection(String actionRef, ConnectionContext connection,
                                                       Object... keyValue) {
        Map<String, Object> parameters = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keyValue.length; i += 2) {
            parameters.put(String.valueOf(keyValue[i]), keyValue[i + 1]);
        }
        return new ActionRequest("tenant-a", "run-1", "node-1", 1, actionRef, "idem-1",
                parameters, Map.of(), connection);
    }
}
