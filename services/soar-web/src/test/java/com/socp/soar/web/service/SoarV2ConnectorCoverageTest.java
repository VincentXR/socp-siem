package com.socp.soar.web.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.socp.platform.client.http.ServiceCall;
import com.socp.platform.client.http.SocpHttpClient;
import com.socp.platform.client.service.AlertClient;
import com.socp.platform.client.service.IncidentClient;
import com.socp.platform.client.service.NotifyClient;
import com.socp.platform.client.service.SearchClient;
import com.socp.platform.client.service.ThreatClient;
import com.socp.platform.tenant.context.TenantContext;
import com.socp.soar.web.connector.EnvironmentSecretResolver;
import com.socp.soar.web.connector.SoarConnectorRegistry;
import com.socp.soar.web.persistence.entity.PlaybookVersionEntity;
import com.socp.soar.web.persistence.entity.SoarConnectorEntity;
import com.socp.soar.web.persistence.repository.PlaybookVersionRepository;
import com.socp.soar.web.persistence.repository.SoarConnectorRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class SoarV2ConnectorCoverageTest {

    @Mock
    private SoarConnectorRepository connectors;
    @Mock
    private PlaybookVersionRepository versions;
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

    private SoarV2ConnectorService service;

    @BeforeEach
    void setUp() {
        TenantContext.set("tenant-a");
        service = new SoarV2ConnectorService(connectors, mapper,
                new SoarConnectorRegistry(alert, incident, notify, search, threat, http,
                        new EnvironmentSecretResolver(), mapper),
                new EnvironmentSecretResolver(), versions);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    // ---------------------------------------------------------------- list / get

    @Test
    void listHidesSoftDeletedRows() {
        given(connectors.findByTenantIdOrderByNameAsc("tenant-a"))
                .willReturn(List.of(row("conn-1", false), row("conn-2", true)));

        List<Map<String, Object>> list = service.list();

        assertThat(list).hasSize(1);
        assertThat(list.get(0)).containsEntry("id", "conn-1")
                .containsEntry("name", "EDR primary")
                .containsEntry("connectorType", "endpoint")
                .containsEntry("authSecretRef", "[REFERENCE]")
                .containsEntry("status", "HEALTHY_UNKNOWN")
                .containsEntry("revision", 2)
                .containsEntry("rowVersion", 7L);
        assertThat(list.get(0).get("secretRefs")).isEqualTo(Map.of("auth", "[REFERENCE]"));
        assertThat(list.get(0).get("allowedHosts")).isEqualTo(List.of("edr.example.com"));
        assertThat(list.get(0).get("config")).isEqualTo(Map.of("tls", "strict"));
    }

    @Test
    void listPagesWithoutLeakingDeletedRows() {
        given(connectors.findByTenantIdOrderByNameAsc("tenant-a")).willReturn(List.of(
                row("conn-1", false), row("conn-2", false), row("conn-3", true)));

        Page<Map<String, Object>> page = service.list(PageRequest.of(1, 2));

        assertThat(page.getTotalElements()).isEqualTo(2);
        assertThat(page.getNumberOfElements()).isEqualTo(0);
        assertThat(page.getContent()).isEmpty();

        Page<Map<String, Object>> first = service.list(PageRequest.of(0, 2));
        assertThat(first.getContent()).hasSize(2);
        assertThat(first.getContent().get(0)).containsEntry("id", "conn-1");
    }

    @Test
    void getReturnsMaskedView() {
        given(connectors.findByTenantIdAndId("tenant-a", "conn-1")).willReturn(Optional.of(row("conn-1", false)));

        Map<String, Object> view = service.get("conn-1");

        assertThat(view).containsEntry("id", "conn-1")
                .containsEntry("endpoint", "https://edr.example.com/api")
                .containsEntry("authSecretRef", "[REFERENCE]")
                .containsEntry("createdBy", "operator")
                .containsEntry("lastTestStatus", "HEALTHY");
        assertThat(view.get("scope")).isEqualTo(Map.of("env", "prod"));
    }

    @Test
    void getRejectsMissingAndDeletedRows() {
        given(connectors.findByTenantIdAndId("tenant-a", "missing")).willReturn(Optional.empty());
        given(connectors.findByTenantIdAndId("tenant-a", "conn-9")).willReturn(Optional.of(row("conn-9", true)));

        assertThatThrownBy(() -> service.get("missing"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("connector not found");
        assertThatThrownBy(() -> service.get("conn-9"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("connector not found");
    }

    // ---------------------------------------------------------------- create

    @Test
    void createPersistsTenantBoundConnectorWithSecretReference() {
        Map<String, Object> created = service.create("EDR primary", "http.webhook",
                "https://hooks.example.com/ingest", "secret://vault/soar/edr",
                List.of("hooks.example.com"), true);

        assertThat(created).containsEntry("name", "EDR primary")
                .containsEntry("connectorType", "HTTP.WEBHOOK")
                .containsEntry("status", "HEALTHY_UNKNOWN")
                .containsEntry("enabled", true)
                .containsEntry("revision", 1);

        SoarConnectorEntity saved = saved();
        assertThat(saved.getTenantId()).isEqualTo("tenant-a");
        assertThat(saved.getConnectorType()).isEqualTo("HTTP.WEBHOOK");
        assertThat(saved.getEndpoint()).isEqualTo("https://hooks.example.com/ingest");
        assertThat(saved.getSecretRefsJson()).isEqualTo("{\"auth\":\"secret://vault/soar/edr\"}");
        assertThat(saved.getAllowedHostsJson()).isEqualTo("[\"hooks.example.com\"]");
        assertThat(saved.getConfigJson()).isEqualTo("{}");
        assertThat(saved.getCreatedBy()).isEqualTo("operator");
        assertThat(saved.getRevision()).isEqualTo(1);
        assertThat(saved.getDeletedAt()).isNull();
    }

    @Test
    void createMarksDisabledConnectorAndAcceptsMissingSecret() {
        service.create("EDR standby", "endpoint", "https://edr.example.com/api", null,
                List.of("edr.example.com"), false);

        SoarConnectorEntity saved = saved();
        assertThat(saved.isEnabled()).isFalse();
        assertThat(saved.getStatus()).isEqualTo("DISABLED");
        assertThat(saved.getAuthSecretRef()).isNull();
        assertThat(saved.getSecretRefsJson()).isEqualTo("{}");
    }

    @Test
    void createRejectsInvalidNamesTypesAndSecretRefs() {
        assertThatThrownBy(() -> service.create("  ", "http.webhook", "https://hooks.example.com/x",
                null, List.of("hooks.example.com"), true))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("connector name is required");
        assertThatThrownBy(() -> service.create("n".repeat(129), "http.webhook", "https://hooks.example.com/x",
                null, List.of("hooks.example.com"), true))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("connector name is required");
        assertThatThrownBy(() -> service.create("Uploaded", "vendor.unknown", "https://hooks.example.com/x",
                null, List.of("hooks.example.com"), true))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("connectorType is not registered");
        assertThatThrownBy(() -> service.create("Uploaded", "http.webhook", " ",
                null, List.of("hooks.example.com"), true))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("connector endpoint is required");
        assertThatThrownBy(() -> service.create("Uploaded", "http.webhook", "https://hooks.example.com/x",
                "s3cr3t-inline-value", List.of("hooks.example.com"), true))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("authSecretRef must be a secret:// reference");
    }

    @Test
    void createRejectsUnsafeAllowlistsAndEndpoints() {
        assertThatThrownBy(() -> service.create("n", "http.webhook", "https://hooks.example.com/x",
                null, List.of(), true))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("allowedHosts must contain 1..32 entries");
        assertThatThrownBy(() -> service.create("n", "http.webhook", "https://hooks.example.com/x",
                null, IntStream.range(0, 33).mapToObj(i -> "h" + i + ".example.com").toList(), true))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("allowedHosts must contain 1..32 entries");
        assertThatThrownBy(() -> service.create("n", "http.webhook", "https://hooks.example.com/x",
                null, List.of("*"), true))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("allowedHosts contains an invalid host pattern");
        assertThatThrownBy(() -> service.create("n", "http.webhook", "https://hooks.example.com/x",
                null, List.of("*.com"), true))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("wildcard allowlist must name a registrable domain");
        assertThatThrownBy(() -> service.create("n", "http.webhook", "https://other.example/x",
                null, List.of("hooks.example.com"), true))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("connector endpoint host is not in the allowlist");
        assertThatThrownBy(() -> service.create("n", "http.webhook", "http://hooks.example.com/x",
                null, List.of("hooks.example.com"), true))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("HTTPS URL without credentials");
        assertThatThrownBy(() -> service.create("n", "http.webhook", "https://127.0.0.1/x",
                null, List.of("127.0.0.1"), true))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("connector endpoint host is not in the allowlist");
    }

    // ---------------------------------------------------------------- update

    @Test
    void updateRejectsStaleRowVersion() {
        SoarConnectorEntity row = row("conn-1", false);
        given(connectors.findByTenantIdAndId("tenant-a", "conn-1")).willReturn(Optional.of(row));

        assertThatThrownBy(() -> service.update("conn-1", "new name", "http.webhook",
                "https://hooks.example.com/x", null, List.of("hooks.example.com"), true,
                row.getRowVersion() + 1))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("connector was changed by another operator");
    }

    @Test
    void updateKeepsExistingSecretReferenceWhenNoneIsSent() {
        SoarConnectorEntity row = row("conn-1", false);
        given(connectors.findByTenantIdAndId("tenant-a", "conn-1")).willReturn(Optional.of(row));

        Map<String, Object> updated = service.update("conn-1", "renamed", "http.webhook",
                "https://hooks.example.com/x", null, List.of("hooks.example.com"), false, 7L);

        assertThat(row.getName()).isEqualTo("renamed");
        assertThat(row.getConnectorType()).isEqualTo("HTTP.WEBHOOK");
        assertThat(row.getAuthSecretRef()).isEqualTo("secret://vault/soar/edr");
        assertThat(row.getSecretRefsJson()).isEqualTo("{\"auth\":\"secret://vault/soar/edr\"}");
        assertThat(row.isEnabled()).isFalse();
        assertThat(row.getStatus()).isEqualTo("DISABLED");
        assertThat(row.getRevision()).isEqualTo(3);
        assertThat(updated).containsEntry("revision", 3).containsEntry("enabled", false);
        verify(connectors).save(row);
    }

    @Test
    void updateClearsSecretReferenceOnExplicitBlankValue() {
        SoarConnectorEntity row = row("conn-1", false);
        given(connectors.findByTenantIdAndId("tenant-a", "conn-1")).willReturn(Optional.of(row));

        service.update("conn-1", "renamed", "http.webhook", "https://hooks.example.com/x",
                "", List.of("hooks.example.com"), true);

        assertThat(row.getAuthSecretRef()).isNull();
        assertThat(row.getSecretRefsJson()).isEqualTo("{}");
        assertThat(row.getStatus()).isEqualTo("HEALTHY_UNKNOWN");
        assertThat(row.getRevision()).isEqualTo(3);
    }

    @Test
    void updateRejectsInvalidFieldsAndMissingRows() {
        SoarConnectorEntity row = row("conn-1", false);
        given(connectors.findByTenantIdAndId("tenant-a", "conn-1")).willReturn(Optional.of(row));
        given(connectors.findByTenantIdAndId("tenant-a", "conn-9")).willReturn(Optional.of(row("conn-9", true)));

        assertThatThrownBy(() -> service.update("conn-1", " ", "http.webhook", "https://hooks.example.com/x",
                null, List.of("hooks.example.com"), true))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("invalid connector fields");
        assertThatThrownBy(() -> service.update("conn-1", "renamed", "vendor.unknown",
                "https://hooks.example.com/x", null, List.of("hooks.example.com"), true))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("connectorType is not registered");
        assertThatThrownBy(() -> service.update("conn-9", "renamed", "http.webhook",
                "https://hooks.example.com/x", null, List.of("hooks.example.com"), true))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("connector not found");
    }

    // ---------------------------------------------------------------- setEnabled

    @Test
    void setEnabledTogglesStatusAndPersists() {
        SoarConnectorEntity row = row("conn-1", false);
        given(connectors.findByTenantIdAndId("tenant-a", "conn-1")).willReturn(Optional.of(row));

        Map<String, Object> view = service.setEnabled("conn-1", false);

        assertThat(row.isEnabled()).isFalse();
        assertThat(row.getStatus()).isEqualTo("DISABLED");
        assertThat(row.getUpdatedAt()).isNotNull();
        assertThat(view).containsEntry("enabled", false).containsEntry("status", "DISABLED");
        verify(connectors).save(row);

        service.setEnabled("conn-1", true);
        assertThat(row.getStatus()).isEqualTo("HEALTHY_UNKNOWN");
    }

    @Test
    void setEnabledRejectsMissingAndDeletedRows() {
        given(connectors.findByTenantIdAndId("tenant-a", "missing")).willReturn(Optional.empty());
        given(connectors.findByTenantIdAndId("tenant-a", "conn-9")).willReturn(Optional.of(row("conn-9", true)));

        assertThatThrownBy(() -> service.setEnabled("missing", true))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("connector not found");
        assertThatThrownBy(() -> service.setEnabled("conn-9", true))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("connector not found");
    }

    // ---------------------------------------------------------------- test

    @Test
    void testRecordsHealthyOutcome() {
        SoarConnectorEntity row = row("conn-1", false);
        row.setConnectorType("socp.alert");
        given(connectors.findByTenantIdAndId("tenant-a", "conn-1")).willReturn(Optional.of(row));

        Map<String, Object> view = service.test("conn-1");

        assertThat(row.getLastTestStatus()).isEqualTo("HEALTHY");
        assertThat(row.getLastTestAt()).isNotNull();
        assertThat(row.getLastTestError()).isNull();
        assertThat(row.getStatus()).isEqualTo("HEALTHY");
        assertThat(view.get("test")).isInstanceOf(Map.class);
        assertThat(asMap(view.get("test"))).containsEntry("healthy", true)
                .containsEntry("status", "HEALTHY");
        verify(connectors).save(row);
    }

    @Test
    void testRecordsUnhealthyOutcomeAndRedactsDiagnosticText() {
        SoarConnectorEntity row = row("conn-1", false);
        // webhook test path exercises the real HTTP egress stub; auth refs are
        // cleared so header building does not fail secret resolution first
        row.setConnectorType("http.webhook");
        row.setAuthSecretRef(null);
        row.setSecretRefsJson("{}");
        given(connectors.findByTenantIdAndId("tenant-a", "conn-1")).willReturn(Optional.of(row));
        given(http.postExternal(anyString(), anyString(), anyString(), anyInt(), anyMap(), anyList()))
                .willReturn(new ServiceCall(null, "https://edr.example.com/api", false, 502, "",
                        "connect failed for Bearer abc123XYZ", 4L, true, 1));

        Map<String, Object> view = service.test("conn-1");

        assertThat(row.getStatus()).isEqualTo("UNHEALTHY");
        assertThat(row.getLastTestStatus()).isEqualTo("UNHEALTHY");
        assertThat(row.getLastTestError()).isEqualTo("connect failed for Bearer [REDACTED]");
        assertThat(asMap(view.get("test"))).containsEntry("healthy", false)
                .containsEntry("status", "UNHEALTHY");
        verify(connectors).save(row);
    }

    @Test
    void testRejectsMissingDeletedRowsAndUnavailableRuntime() {
        given(connectors.findByTenantIdAndId("tenant-a", "missing")).willReturn(Optional.empty());
        given(connectors.findByTenantIdAndId("tenant-a", "conn-9")).willReturn(Optional.of(row("conn-9", true)));
        given(connectors.findByTenantIdAndId("tenant-a", "conn-1")).willReturn(Optional.of(row("conn-1", false)));

        assertThatThrownBy(() -> service.test("missing"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("connector not found");
        assertThatThrownBy(() -> service.test("conn-9"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("connector not found");
        assertThatThrownBy(() -> new SoarV2ConnectorService(connectors, mapper).test("conn-1"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("connector runtime is unavailable");
    }

    // ---------------------------------------------------------------- softDelete

    @Test
    void softDeleteDisablesAndStampsDeletedAt() {
        SoarConnectorEntity row = row("conn-1", false);
        given(connectors.findByTenantIdAndId("tenant-a", "conn-1")).willReturn(Optional.of(row));

        Map<String, Object> view = service.softDelete("conn-1");

        assertThat(row.getDeletedAt()).isNotNull();
        assertThat(row.isEnabled()).isFalse();
        assertThat(row.getStatus()).isEqualTo("DELETED");
        assertThat(view).containsEntry("status", "DELETED");
        verify(connectors).save(row);
    }

    @Test
    void softDeleteRejectsMissingAndAlreadyDeletedRows() {
        given(connectors.findByTenantIdAndId("tenant-a", "missing")).willReturn(Optional.empty());
        given(connectors.findByTenantIdAndId("tenant-a", "conn-9")).willReturn(Optional.of(row("conn-9", true)));

        assertThatThrownBy(() -> service.softDelete("missing"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("connector not found");
        assertThatThrownBy(() -> service.softDelete("conn-9"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("connector not found");
    }

    @Test
    void softDeleteKeepsConnectionsReferencedByPublishedVersions() {
        SoarConnectorEntity row = row("conn-1", false);
        given(connectors.findByTenantIdAndId("tenant-a", "conn-1")).willReturn(Optional.of(row));
        given(versions.findByTenantId("tenant-a")).willReturn(List.of(
                published("{\"nodes\":[{\"connectionRef\":\"conn-1\"}]}")));

        assertThatThrownBy(() -> service.softDelete("conn-1"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("connector is referenced by a published playbook version");
        assertThat(row.getDeletedAt()).isNull();
    }

    @Test
    void softDeleteFailsClosedOnUnparseablePublishedDefinition() {
        SoarConnectorEntity row = row("conn-1", false);
        given(connectors.findByTenantIdAndId("tenant-a", "conn-1")).willReturn(Optional.of(row));
        given(versions.findByTenantId("tenant-a")).willReturn(List.of(published("not-json")));

        assertThatThrownBy(() -> service.softDelete("conn-1"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("connector is referenced by a published playbook version");
    }

    @Test
    void softDeleteIgnoresDraftsAndUnrelatedConnections() {
        SoarConnectorEntity row = row("conn-1", false);
        given(connectors.findByTenantIdAndId("tenant-a", "conn-1")).willReturn(Optional.of(row));
        PlaybookVersionEntity draft = published("{\"nodes\":[{\"connectionRef\":\"conn-1\"}]}");
        draft.setStatus("DRAFT");
        given(versions.findByTenantId("tenant-a")).willReturn(List.of(draft,
                published("{\"nodes\":[{\"connectionRef\":\"conn-other\"}]}")));

        service.softDelete("conn-1");

        assertThat(row.getStatus()).isEqualTo("DELETED");
    }

    // ---------------------------------------------------------------- actions

    @Test
    void actionsExposeFullConnectorMetadata() {
        List<Map<String, Object>> actions = service.actions();

        assertThat(actions).isNotEmpty();
        assertThat(actions).allSatisfy(action -> assertThat(action).containsKeys("connectorId",
                "connectorVersion", "production", "actionRef", "id", "displayName", "description",
                "riskLevel", "sideEffect", "idempotency", "requiresConnection", "requiredPermissions",
                "requestTimeoutSeconds", "retryCap", "payloadCapBytes", "sensitiveOutputFields",
                "supportsReconcile", "supportsCompensate", "inputSchema", "outputSchema"));

        Map<String, Object> isolate = actions.stream()
                .filter(action -> "endpoint/isolate-host@1".equals(action.get("actionRef")))
                .findFirst().orElseThrow();
        assertThat(isolate).containsEntry("connectorId", "endpoint")
                .containsEntry("connectorVersion", 1)
                .containsEntry("production", false)
                .containsEntry("id", "isolate-host")
                .containsEntry("displayName", "Isolate host")
                .containsEntry("riskLevel", "HIGH")
                .containsEntry("sideEffect", "REVERSIBLE")
                .containsEntry("idempotency", "NATIVE")
                .containsEntry("requiresConnection", true)
                .containsEntry("requiredPermissions", List.of("soar:execute", "soar:approve"))
                .containsEntry("requestTimeoutSeconds", 60)
                .containsEntry("retryCap", 3)
                .containsEntry("payloadCapBytes", 10L * 1024 * 1024)
                .containsEntry("sensitiveOutputFields", List.of())
                .containsEntry("supportsReconcile", false)
                .containsEntry("supportsCompensate", false);
        assertThat(isolate.get("inputSchema")).isNotNull();
        assertThat(isolate.get("outputSchema")).isNotNull();
    }

    @Test
    void actionsAreEmptyWithoutAConnectorRuntime() {
        assertThat(new SoarV2ConnectorService(connectors, mapper).actions()).isEmpty();
    }

    // ---------------------------------------------------------------- helpers

    private SoarConnectorEntity saved() {
        ArgumentCaptor<SoarConnectorEntity> captor = ArgumentCaptor.forClass(SoarConnectorEntity.class);
        verify(connectors).save(captor.capture());
        return captor.getValue();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return (Map<String, Object>) value;
    }

    private static PlaybookVersionEntity published(String definitionJson) {
        PlaybookVersionEntity version = new PlaybookVersionEntity();
        version.setId("ver-1");
        version.setTenantId("tenant-a");
        version.setPlaybookId("pb-1");
        version.setVersionNo(1);
        version.setStatus("PUBLISHED");
        version.setDefinitionJson(definitionJson);
        version.setCreatedAt(Instant.now());
        version.setUpdatedAt(Instant.now());
        return version;
    }

    private static SoarConnectorEntity row(String id, boolean deleted) {
        SoarConnectorEntity row = new SoarConnectorEntity();
        row.setId(id);
        row.setTenantId("tenant-a");
        row.setName("EDR primary");
        row.setConnectorType("endpoint");
        row.setEndpoint("https://edr.example.com/api");
        row.setAuthSecretRef("secret://vault/soar/edr");
        row.setConfigJson("{\"tls\":\"strict\"}");
        row.setSecretRefsJson("{\"auth\":\"secret://vault/soar/edr\"}");
        row.setScopeJson("{\"env\":\"prod\"}");
        row.setAllowedHostsJson("[\"edr.example.com\"]");
        row.setEnabled(true);
        row.setStatus("HEALTHY_UNKNOWN");
        row.setRevision(2);
        row.setLastTestStatus("HEALTHY");
        row.setCreatedBy("operator");
        row.setCreatedAt(Instant.now());
        row.setUpdatedAt(Instant.now());
        row.setRowVersion(7L);
        if (deleted) {
            row.setDeletedAt(Instant.now());
        }
        return row;
    }
}
