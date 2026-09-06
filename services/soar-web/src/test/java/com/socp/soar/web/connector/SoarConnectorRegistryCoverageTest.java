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

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class SoarConnectorRegistryCoverageTest {

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

    private final ObjectMapper mapper = new ObjectMapper();

    private SoarConnectorRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new SoarConnectorRegistry(alert, incident, notify, search, threat, http,
                new EnvironmentSecretResolver(), mapper);
    }

    @Test
    void descriptorsAreSortedByIdAndExposeBuiltInConnectors() {
        List<ConnectorDescriptor> descriptors = registry.descriptors();

        assertThat(descriptors).hasSize(9);
        assertThat(descriptors.stream().map(ConnectorDescriptor::id).toList()).isSorted();
        assertThat(descriptors.stream().map(ConnectorDescriptor::id))
                .contains("endpoint", "firewall", "http.webhook", "socp.alert", "socp.incident");
        assertThat(descriptors).allSatisfy(descriptor ->
                assertThat(descriptor.majorVersion()).isEqualTo(1));
        ConnectorDescriptor endpoint = descriptors.stream()
                .filter(descriptor -> "endpoint".equals(descriptor.id())).findFirst().orElseThrow();
        assertThat(endpoint.production()).isFalse();
        assertThat(endpoint.actions()).extracting(ActionDescriptor::id)
                .containsExactly("isolate-host", "release-host", "snapshot");
    }

    @Test
    void findNormalizesAliasesAndRejectsUnknownIds() {
        assertThat(registry.find("  FIREWALL ").orElseThrow().descriptor().id()).isEqualTo("firewall");
        assertThat(registry.find("net.firewall").orElseThrow().descriptor().id()).isEqualTo("firewall");
        assertThat(registry.find("http.webhook")).isPresent();
        assertThat(registry.find("vendor.unknown")).isEmpty();
        assertThat(registry.find(null)).isEmpty();
    }

    @Test
    void descriptorForActionResolvesKnownRefs() {
        assertThat(registry.descriptorForAction("socp.alert/add-tag").orElseThrow().id()).isEqualTo("socp.alert");
        assertThat(registry.descriptorForAction("socp.alert/get@1").orElseThrow().id()).isEqualTo("socp.alert");
        assertThat(registry.descriptorForAction("socp.alert/get@v1")).isPresent();
        assertThat(registry.descriptorForAction("endpoint/isolate").orElseThrow().id()).isEqualTo("endpoint");
    }

    @Test
    void descriptorForActionRejectsUnknownAndVersionMismatch() {
        assertThat(registry.descriptorForAction("socp.alert/nope")).isEmpty();
        assertThat(registry.descriptorForAction("vendor.unknown/get")).isEmpty();
        assertThat(registry.descriptorForAction("nonsense")).isEmpty();
        assertThat(registry.descriptorForAction("socp.alert/get@2")).isEmpty();
        assertThat(registry.descriptorForAction(null)).isEmpty();
    }

    @Test
    void canonicalActionRefMapsLegacySpellings() {
        assertThat(registry.canonicalActionRef("http/webhook")).isEqualTo("http.webhook/request");
        assertThat(registry.canonicalActionRef("net.firewall/block")).isEqualTo("firewall/block-ioc");
        assertThat(registry.canonicalActionRef("socp.notify/send")).isEqualTo("socp.notify/send-channel");
        assertThat(registry.canonicalActionRef("socp.threat-intel/ioc.lookup"))
                .isEqualTo("socp.threat-intel/lookup-ioc");
        assertThat(registry.canonicalActionRef("endpoint/isolate")).isEqualTo("endpoint/isolate-host");
        assertThat(registry.canonicalActionRef("endpoint/snapshot-host")).isEqualTo("endpoint/snapshot");
        assertThat(registry.canonicalActionRef("socp.alert/get")).isEqualTo("socp.alert/get");
    }

    @Test
    void actionDescriptorExposesConcreteMetadata() {
        ActionDescriptor isolate = registry.actionDescriptor("endpoint/isolate-host").orElseThrow();

        assertThat(isolate.id()).isEqualTo("isolate-host");
        assertThat(isolate.displayName()).isEqualTo("Isolate host");
        assertThat(isolate.riskLevel()).isEqualTo("HIGH");
        assertThat(isolate.sideEffect()).isEqualTo("REVERSIBLE");
        assertThat(isolate.idempotency()).isEqualTo("NATIVE");
        assertThat(isolate.requiresConnection()).isTrue();
        assertThat(isolate.requiredPermissions()).containsExactly("soar:execute", "soar:approve");
        assertThat(isolate.requestTimeoutSeconds()).isEqualTo(60);
        assertThat(isolate.retryCap()).isEqualTo(3);
        assertThat(isolate.payloadCapBytes()).isEqualTo(10L * 1024 * 1024);
        assertThat(isolate.sensitiveOutputFields()).isEmpty();
        assertThat(isolate.supportsReconcile()).isFalse();
        assertThat(isolate.supportsCompensate()).isFalse();
        assertThat(isolate.inputSchema()).containsKey("properties");
        assertThat(isolate.outputSchema()).containsKey("properties");
        assertThat(isolate.ref("endpoint")).isEqualTo("endpoint/isolate-host@1");

        ActionDescriptor read = registry.actionDescriptor("socp.alert/get").orElseThrow();
        assertThat(read.riskLevel()).isEqualTo("READ_ONLY");
        assertThat(read.sideEffect()).isEqualTo("NONE");
        assertThat(read.requiresConnection()).isFalse();
        assertThat(read.requiredPermissions()).containsExactly("soar:execute");
    }

    @Test
    void actionDescriptorIsEmptyForUnknownRefs() {
        assertThat(registry.actionDescriptor("socp.alert/nope")).isEmpty();
        assertThat(registry.actionDescriptor("vendor.unknown/get")).isEmpty();
        assertThat(registry.actionDescriptor("nonsense")).isEmpty();
        assertThat(registry.actionDescriptor(null)).isEmpty();
    }

    @Test
    void executeDispatchesToNativeAlertConnector() {
        given(alert.getAlarm("ALM-1")).willReturn(call(200, "{\"id\":\"ALM-1\",\"status\":\"OPEN\"}"));
        given(alert.addTag("ALM-1", "contained", "idem-1"))
                .willReturn(call(200, "{\"operationId\":\"OP-1\"}"));

        ActionResult read = registry.execute(request("socp.alert/get"));
        assertThat(read.status()).isEqualTo("SUCCEEDED");
        assertThat(read.operationId()).isEqualTo("ALM-1");
        assertThat(read.output()).containsEntry("status", "OPEN");

        ActionResult tag = registry.execute(request("socp.alert/add-tag", Map.of("alertId", "ALM-1", "tag", "contained")));
        assertThat(tag.status()).isEqualTo("SUCCEEDED");
        assertThat(tag.operationId()).isEqualTo("OP-1");
        assertThat(tag.receipt()).containsEntry("action", "add-tag").containsEntry("httpStatus", 200);
        assertThat(tag.retryable()).isFalse();
        assertThat(tag.errorCode()).isNull();
    }

    @Test
    void executeDispatchesToIncidentSearchThreatAndNotify() {
        given(incident.list()).willReturn(call(200, "{\"items\":[]}"));
        given(search.search("tenant-a")).willReturn(call(200, "{\"total\":2}"));
        given(threat.matchIocs("[\"1.2.3.4\",\"evil.com\"]")).willReturn(call(200, "{\"operationId\":\"IOC-1\"}"));
        given(notify.notifyAlert(anyString(), eq("idem-1"))).willReturn(call(200, "{\"operationId\":\"NOTIFY-1\"}"));

        assertThat(registry.execute(request("socp.incident/get")).status()).isEqualTo("SUCCEEDED");
        assertThat(registry.execute(request("socp.search/search-events", Map.of("query", "tenant-a"))).status())
                .isEqualTo("SUCCEEDED");
        ActionResult lookup = registry.execute(request("socp.threat-intel/lookup-ioc",
                Map.of("ioc", List.of("1.2.3.4", "evil.com"))));
        assertThat(lookup.operationId()).isEqualTo("IOC-1");
        assertThat(registry.execute(request("socp.notify/send-channel", Map.of("message", "hi"))).operationId())
                .isEqualTo("NOTIFY-1");
        assertThat(registry.execute(request("socp.notify/send", Map.of("message", "hi"))).operationId())
                .isEqualTo("NOTIFY-1");
    }

    @Test
    void executeDispatchesToAssetCollection() {
        given(http.get(SocpService.ASSET, "/api/v1/assets"))
                .willReturn(call(200, "[{\"id\":\"asset-1\",\"name\":\"web-1\",\"ip\":\"10.0.0.5\"}]"));

        // target.id takes priority over parameters.entity in the selector, so
        // this request uses an empty target to exercise the entity selector
        ActionResult found = registry.execute(new ActionRequest("tenant-a", "run-1", "node-1", 1,
                "socp.asset/find-by-entity", "idem-1", Map.of("entity", "web-1"), Map.of(), null));
        assertThat(found.status()).isEqualTo("SUCCEEDED");
        assertThat(found.operationId()).isEqualTo("asset-1");
        assertThat(found.output()).containsEntry("count", 1).containsEntry("inspected", 1)
                .containsEntry("truncated", false);

        given(http.get(SocpService.ASSET, "/api/v1/assets"))
                .willReturn(call(200, "[{\"id\":\"asset-1\"},{\"id\":\"asset-2\"}]"));
        ActionResult byId = registry.execute(request("socp.asset/get-asset", Map.of("assetId", "asset-2")));
        assertThat(byId.operationId()).isEqualTo("asset-2");
    }

    @Test
    void executeMapsAssetFailures() {
        given(http.get(SocpService.ASSET, "/api/v1/assets")).willReturn(null);
        assertThat(registry.execute(request("socp.asset/get-asset")))
                .satisfies(result -> {
                    assertThat(result.status()).isEqualTo("FAILED");
                    assertThat(result.errorCode()).isEqualTo("SERVICE_NO_RESULT");
                    assertThat(result.retryable()).isTrue();
                });

        given(http.get(SocpService.ASSET, "/api/v1/assets"))
                .willReturn(new ServiceCall(null, "url", false, 503, "", "upstream down", 5L, true, 1));
        ActionResult failed = registry.execute(request("socp.asset/get-asset"));
        assertThat(failed.errorCode()).isEqualTo("SERVICE_CALL_FAILED");
        assertThat(failed.errorMessage()).isEqualTo("upstream down");
        assertThat(failed.retryable()).isTrue();

        given(http.get(SocpService.ASSET, "/api/v1/assets")).willReturn(call(200, "{\"items\":\"nope\"}"));
        assertThat(registry.execute(request("socp.asset/get-asset")).errorCode())
                .isEqualTo("MISSING_CONNECTOR_RECEIPT");

        given(http.get(SocpService.ASSET, "/api/v1/assets")).willReturn(call(200, "not-json"));
        assertThat(registry.execute(request("socp.asset/get-asset")).errorCode())
                .isEqualTo("SERVICE_RESPONSE_INVALID");
    }

    @Test
    void executeRejectsInvalidUnknownAndVersionedRefs() {
        ActionResult invalid = registry.execute(request("nonsense"));
        assertThat(invalid.errorCode()).isEqualTo("SOAR_ACTION_NOT_FOUND");
        assertThat(invalid.errorMessage()).isEqualTo("invalid action ref");
        assertThat(invalid.retryable()).isFalse();

        ActionResult unknownConnector = registry.execute(request("vendor.unknown/get"));
        assertThat(unknownConnector.errorCode()).isEqualTo("SOAR_ACTION_NOT_FOUND");
        assertThat(unknownConnector.errorMessage()).isEqualTo("unknown connector");

        ActionResult unknownAction = registry.execute(request("socp.alert/nope"));
        assertThat(unknownAction.errorCode()).isEqualTo("SOAR_ACTION_NOT_FOUND");
        assertThat(unknownAction.errorMessage()).isEqualTo("unknown action");

        ActionResult wrongVersion = registry.execute(request("socp.alert/get@9"));
        assertThat(wrongVersion.errorCode()).isEqualTo("SOAR_ACTION_VERSION_UNAVAILABLE");
        assertThat(wrongVersion.errorMessage()).isEqualTo("unsupported connector major version");
    }

    @Test
    void executeMapsConnectorFailures() {
        given(notify.notifyAlert(anyString(), anyString()))
                .willThrow(new IllegalStateException("SOAR_SECRET_RESOLUTION_FAILED: auth"));
        ActionResult secret = registry.execute(request("socp.notify/send-channel"));
        assertThat(secret.status()).isEqualTo("FAILED");
        assertThat(secret.errorCode()).isEqualTo("SOAR_SECRET_RESOLUTION_FAILED");
        assertThat(secret.retryable()).isTrue();

        given(search.search(anyString())).willThrow(new IllegalStateException("boom"));
        ActionResult generic = registry.execute(request("socp.search/search-events"));
        assertThat(generic.errorCode()).isEqualTo("CONNECTOR_EXCEPTION");
        assertThat(generic.errorMessage()).isEqualTo("boom");
        assertThat(generic.retryable()).isTrue();
        assertThat(generic.status()).isEqualTo("FAILED");

        given(incident.list()).willThrow(new IllegalStateException());
        assertThat(registry.execute(request("socp.incident/get")).errorMessage())
                .isEqualTo("connector call failed");
    }

    @Test
    void executeRejectsExternalActionWithoutConnection() {
        ActionResult webhook = registry.execute(request("http/webhook"));
        assertThat(webhook.errorCode()).isEqualTo("SOAR_CONNECTION_UNAVAILABLE");
        assertThat(webhook.errorMessage()).isEqualTo("connection is required");

        ActionResult endpoint = registry.execute(request("endpoint/isolate-host"));
        assertThat(endpoint.errorCode()).isEqualTo("SOAR_CONNECTION_UNAVAILABLE");
    }

    @Test
    void executeMapsExternalCallOutcomes() {
        given(http.postExternal(anyString(), anyString(), anyString(), anyInt(), anyMap(), anyList()))
                .willReturn(call(200, "{\"operationId\":\"OP-9\"}"));
        ActionResult ok = registry.execute(new ActionRequest("tenant-a", "run-1", "node-1", 1,
                "endpoint/isolate-host", "idem-1", Map.of("host", "web-1"), Map.of("id", "web-1"),
                connection("https://edr.example.com/api")));
        assertThat(ok.status()).isEqualTo("SUCCEEDED");
        assertThat(ok.operationId()).isEqualTo("OP-9");

        given(http.postExternal(anyString(), anyString(), anyString(), anyInt(), anyMap(), anyList()))
                .willReturn(call(200, "{\"ok\":true}"));
        ActionResult noReceipt = registry.execute(new ActionRequest("tenant-a", "run-1", "node-1", 1,
                "endpoint/isolate-host", "idem-1", Map.of(), Map.of(),
                connection("https://edr.example.com/api")));
        assertThat(noReceipt.errorCode()).isEqualTo("MISSING_CONNECTOR_RECEIPT");
        assertThat(noReceipt.retryable()).isFalse();

        given(http.postExternal(anyString(), anyString(), anyString(), anyInt(), anyMap(), anyList()))
                .willReturn(new ServiceCall(null, "https://edr.example.com/api", false, -1, "",
                        "ConnectException: timeout", 7L, true, 1));
        ActionResult unknown = registry.execute(new ActionRequest("tenant-a", "run-1", "node-1", 1,
                "endpoint/isolate-host", "idem-1", Map.of(), Map.of(),
                connection("https://edr.example.com/api")));
        assertThat(unknown.status()).isEqualTo("UNKNOWN");
        assertThat(unknown.errorCode()).isEqualTo("REMOTE_RESULT_UNKNOWN");
        assertThat(unknown.errorMessage()).isEqualTo("ConnectException: timeout");
    }

    @Test
    void reconcileIsAdvisoryAndNeverGuesses() {
        assertThat(registry.reconcile(null)).isEmpty();
        assertThat(registry.reconcile(query("nonsense"))).isEmpty();
        assertThat(registry.reconcile(query("vendor.unknown/get"))).isEmpty();
        assertThat(registry.reconcile(query("socp.alert/get"))).isEmpty();
        assertThat(registry.reconcile(query("endpoint/isolate-host"))).isEmpty();
    }

    @Test
    void compensateReturnsEmptyForUnknownRefsAndBuiltInActions() {
        ActionRequest request = request("socp.alert/add-tag");

        assertThat(registry.compensate(null, "socp.alert/add-tag")).isEmpty();
        assertThat(registry.compensate(request, null)).isEmpty();
        assertThat(registry.compensate(request, "   ")).isEmpty();
        assertThat(registry.compensate(request, "nonsense")).isEmpty();
        assertThat(registry.compensate(request, "vendor.unknown/undo")).isEmpty();
        assertThat(registry.compensate(request, "socp.alert/nope")).isEmpty();
        assertThat(registry.compensate(request, "socp.alert/add-tag")).isEmpty();
    }

    @Test
    void testReportsHealthAndFailures() {
        ConnectionTestResult healthy = registry.test("socp.alert", null);
        assertThat(healthy.healthy()).isTrue();
        assertThat(healthy.status()).isEqualTo("HEALTHY");
        assertThat(healthy.errorCode()).isNull();
        assertThat(healthy.details()).containsEntry("connector", "socp.alert");
        assertThat(healthy.testedAt()).isNotNull();

        ConnectionTestResult unknown = registry.test("vendor.unknown", null);
        assertThat(unknown.healthy()).isFalse();
        assertThat(unknown.status()).isEqualTo("UNHEALTHY");
        assertThat(unknown.errorCode()).isEqualTo("SOAR_ACTION_NOT_FOUND");
        assertThat(unknown.errorMessage()).isEqualTo("unknown connector");

        assertThat(registry.test("net.firewall", null).errorCode()).isEqualTo("SOAR_CONNECTION_UNAVAILABLE");

        given(http.postExternal(anyString(), anyString(), anyString(), anyInt(), anyMap(), anyList()))
                .willReturn(call(204, ""));
        ConnectionTestResult external = registry.test("endpoint", connection("https://edr.example.com/api"));
        assertThat(external.healthy()).isTrue();
        assertThat(external.details()).containsEntry("status", 204);

        given(http.postExternal(anyString(), anyString(), anyString(), anyInt(), anyMap(), anyList()))
                .willReturn(new ServiceCall(null, "https://edr.example.com/api", false, 502, "", "blocked", 3L, true, 1));
        ConnectionTestResult unhealthy = registry.test("endpoint", connection("https://edr.example.com/api"));
        assertThat(unhealthy.healthy()).isFalse();
        assertThat(unhealthy.errorCode()).isEqualTo("SOAR_EGRESS_DENIED");
        assertThat(unhealthy.errorMessage()).isEqualTo("blocked");
    }

    @Test
    void testMapsConnectorExceptionsWithoutLeaking() {
        given(http.postExternal(anyString(), anyString(), anyString(), anyInt(), anyMap(), anyList()))
                .willThrow(new IllegalStateException("SOAR_SECRET_RESOLUTION_FAILED: auth"));

        ConnectionTestResult result = registry.test("endpoint", connection("https://edr.example.com/api"));

        assertThat(result.healthy()).isFalse();
        assertThat(result.errorCode()).isEqualTo("SOAR_SECRET_RESOLUTION_FAILED");
        assertThat(result.errorMessage()).isEqualTo("SOAR_SECRET_RESOLUTION_FAILED: auth");
    }

    private static ActionRequest request(String ref) {
        return request(ref, Map.of("alertId", "ALM-1"));
    }

    private static ActionRequest request(String ref, Map<String, Object> parameters) {
        return new ActionRequest("tenant-a", "run-1", "node-1", 1, ref, "idem-1", parameters,
                Map.of("id", "ALM-1"), null);
    }

    private static ActionQuery query(String ref) {
        return new ActionQuery("tenant-a", "run-1", "node-1", ref, "idem-1", Map.of(), Map.of());
    }

    private static ConnectionContext connection(String endpoint) {
        return new ConnectionContext("tenant-a", "conn-1", 1, "endpoint", endpoint,
                Map.of(), Map.of(), reference -> Optional.empty(), Duration.ofSeconds(30),
                List.of("edr.example.com"));
    }

    private static ServiceCall call(int status, String body) {
        return new ServiceCall(null, "https://internal/api", status >= 200 && status < 300, status, body,
                null, 3L, false, 1);
    }
}
