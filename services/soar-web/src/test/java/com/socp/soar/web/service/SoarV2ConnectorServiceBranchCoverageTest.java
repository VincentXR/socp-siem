package com.socp.soar.web.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.socp.platform.tenant.context.TenantContext;
import com.socp.soar.web.connector.EnvironmentSecretResolver;
import com.socp.soar.web.connector.SoarConnector;
import com.socp.soar.web.connector.SoarConnectorRegistry;
import com.socp.soar.web.persistence.entity.SoarConnectorEntity;
import com.socp.soar.web.persistence.repository.PlaybookVersionRepository;
import com.socp.soar.web.persistence.repository.SoarConnectorRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

/** Branch coverage for connector validation, masking, and read fallbacks. */
@ExtendWith(MockitoExtension.class)
class SoarV2ConnectorServiceBranchCoverageTest {

    @Mock
    private SoarConnectorRepository connectors;
    @Mock
    private SoarConnectorRegistry registry;
    @Mock
    private EnvironmentSecretResolver secrets;
    @Mock
    private PlaybookVersionRepository versions;

    private final ObjectMapper mapper = new ObjectMapper();
    private SoarV2ConnectorService service;

    @BeforeEach
    void setUp() {
        TenantContext.set("tenant-a");
        service = new SoarV2ConnectorService(connectors, mapper, registry, secrets, versions);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void compatibilityConstructorLeavesVersionRepositoryNull() {
        SoarV2ConnectorService legacy = new SoarV2ConnectorService(connectors, mapper, registry, secrets);
        assertThat(legacy).isNotNull();
        // The compatibility constructor delegates with a null version store;
        // soft delete then skips the published-version scan entirely.
        given(connectors.findByTenantIdAndIdForUpdate("tenant-a", "c1"))
                .willReturn(Optional.of(entity()));
        assertThat(legacy.softDelete("c1")).containsEntry("status", "DELETED");
    }

    @Test
    void createRejectsBlankConnectorType() {
        assertThatThrownBy(() -> service.create("conn", "  ", "https://hooks.example.test/x",
                null, List.of("hooks.example.test"), true))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST))
                .hasMessageContaining("connectorType is required (max 64)");
    }

    @Test
    void updateRejectsEmptyAllowedHostsAndBadSecretRef() {
        SoarConnectorEntity row = entity();
        given(connectors.findByTenantIdAndIdForUpdate("tenant-a", "c1")).willReturn(Optional.of(row));
        given(registry.find("http.webhook"))
                .willReturn(Optional.of(org.mockito.Mockito.mock(SoarConnector.class)));

        assertThatThrownBy(() -> service.update("c1", "conn", "http.webhook",
                "https://hooks.example.test/x", null, List.of(), true))
                .hasMessageContaining("allowedHosts must contain 1..32 entries");

        assertThatThrownBy(() -> service.update("c1", "conn", "http.webhook",
                "https://hooks.example.test/x", "not-a-ref", List.of("hooks.example.test"), true))
                .hasMessageContaining("authSecretRef must be a secret:// reference");
    }

    @Test
    void createRejectsUnresolvableNumericHostLiteral() {
        given(registry.find("http.webhook"))
                .willReturn(Optional.of(org.mockito.Mockito.mock(SoarConnector.class)));

        // "1.2.3.4.5" is a numeric literal the JDK cannot resolve; java.net.URI
        // parses it with a null host, so the policy fails closed on the HTTPS
        // URL shape check before the allowlist comparison is ever reached.
        assertThatThrownBy(() -> service.create("conn", "http.webhook", "https://1.2.3.4.5/x",
                null, List.of("1.2.3.4.5"), true))
                .hasMessageContaining("must be an HTTPS URL without credentials");
    }

    @Test
    void viewFallsBackToEmptyStructuresForCorruptJsonColumns() {
        SoarConnectorEntity row = entity();
        row.setConfigJson("not-json{");
        row.setSecretRefsJson("not-json{");
        row.setScopeJson("not-json{");
        row.setAllowedHostsJson("not-json{");
        given(connectors.findByTenantIdAndIdForUpdate("tenant-a", "c1")).willReturn(Optional.of(row));

        Map<String, Object> view = service.setEnabled("c1", false);

        assertThat(view).containsEntry("config", Map.of())
                .containsEntry("secretRefs", Map.of())
                .containsEntry("scope", Map.of())
                .containsEntry("allowedHosts", List.of())
                .containsEntry("status", "DISABLED");
    }

    private static SoarConnectorEntity entity() {
        SoarConnectorEntity row = new SoarConnectorEntity();
        row.setId("c1");
        row.setTenantId("tenant-a");
        row.setName("conn");
        row.setConnectorType("HTTP.WEBHOOK");
        row.setEndpoint("https://hooks.example.test/x");
        row.setAuthSecretRef("secret://vault/token");
        row.setConfigJson("{}");
        row.setSecretRefsJson("{\"auth\":\"secret://vault/token\"}");
        row.setScopeJson("{}");
        row.setAllowedHostsJson("[\"hooks.example.test\"]");
        row.setEnabled(true);
        row.setStatus("HEALTHY_UNKNOWN");
        row.setRevision(1);
        row.setCreatedBy("operator");
        row.setCreatedAt(Instant.now());
        row.setUpdatedAt(Instant.now());
        row.setRowVersion(1L);
        return row;
    }
}
