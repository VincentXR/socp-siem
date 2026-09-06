package com.socp.soar.web.api.controller;

import com.socp.platform.error.api.ApiResult;
import com.socp.soar.web.config.SoarRuntimeProperties;
import com.socp.soar.web.connector.ConnectorDescriptor;
import com.socp.soar.web.connector.SoarConnectorRegistry;
import com.socp.soar.web.persistence.repository.SoarDispatchOutboxRepository;
import com.socp.soar.web.persistence.repository.SoarSignalOutboxRepository;
import com.socp.soar.web.service.TemporalExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

/** Detailed /health payload coverage: temporal status, backlogs, connector list, checkedAt. */
@ExtendWith(MockitoExtension.class)
class HealthControllerDetailCoverageTest {

    @Mock
    private ObjectProvider<org.springframework.boot.actuate.health.HealthEndpoint> healthEndpoint;
    @Mock
    private ObjectProvider<TemporalExecutor> temporalProvider;
    @Mock
    private ObjectProvider<SoarDispatchOutboxRepository> dispatchProvider;
    @Mock
    private ObjectProvider<SoarSignalOutboxRepository> signalProvider;
    @Mock
    private ObjectProvider<SoarConnectorRegistry> connectorProvider;
    @Mock
    private TemporalExecutor temporal;
    @Mock
    private SoarDispatchOutboxRepository dispatches;
    @Mock
    private SoarSignalOutboxRepository signals;
    @Mock
    private SoarConnectorRegistry registry;

    private SoarRuntimeProperties properties;

    @BeforeEach
    void setUp() {
        properties = new SoarRuntimeProperties();
        properties.setMaturity("ga");
    }

    @Test
    void reportsFullDetailsWhenTemporalBacklogsAndConnectorsAreAvailable() {
        given(temporalProvider.getIfAvailable()).willReturn(temporal);
        given(temporal.isAvailable()).willReturn(true);
        given(dispatchProvider.getIfAvailable()).willReturn(dispatches);
        given(dispatches.countByStatus("PENDING")).willReturn(3L);
        given(dispatches.countByStatus("DEAD")).willReturn(1L);
        given(signalProvider.getIfAvailable()).willReturn(signals);
        given(signals.countByStatus("PENDING")).willReturn(5L);
        given(signals.countByStatus("DEAD")).willReturn(2L);
        given(connectorProvider.getIfAvailable()).willReturn(registry);
        given(registry.descriptors()).willReturn(List.of(
                new ConnectorDescriptor("endpoint", 1, "Endpoint Response", false, List.of()),
                new ConnectorDescriptor("firewall", 1, "Firewall Response", false, List.of())));

        ApiResult<Map<String, Object>> result = new HealthController(
                properties, healthEndpoint, temporalProvider, dispatchProvider,
                signalProvider, connectorProvider).health();

        Map<String, Object> details = result.data();
        assertThat(details.get("service")).isEqualTo("soar-web");
        assertThat(details.get("status")).isEqualTo("UP");
        assertThat(details.get("platform")).isEqualTo("UP");
        assertThat(details.get("maturity")).isEqualTo("ga");
        assertThat(details.get("temporal")).isEqualTo(Map.of("status", "UP"));
        assertThat(details.get("dispatchBacklog")).isEqualTo(3L);
        assertThat(details.get("dispatchDead")).isEqualTo(1L);
        assertThat(details.get("signalBacklog")).isEqualTo(5L);
        assertThat(details.get("signalDead")).isEqualTo(2L);
        assertThat(details.get("builtInConnectors")).isEqualTo(List.of("endpoint", "firewall"));
        assertThat(details.get("checkedAt")).isInstanceOf(Instant.class);
    }

    @Test
    void reportsDegradedStatusAndSkipsUnavailableProviders() {
        given(temporalProvider.getIfAvailable()).willReturn(temporal);
        given(temporal.isAvailable()).willReturn(false);

        ApiResult<Map<String, Object>> result = new HealthController(
                properties, healthEndpoint, temporalProvider, dispatchProvider,
                signalProvider, connectorProvider).health();

        Map<String, Object> details = result.data();
        assertThat(details.get("status")).isEqualTo("DEGRADED");
        assertThat(details.get("platform")).isEqualTo("UP");
        assertThat(details.get("temporal")).isEqualTo(Map.of("status", "UNAVAILABLE"));
        // Providers are present but resolve to no bean, so the backlog and
        // connector sections must be omitted instead of failing the probe.
        assertThat(details).doesNotContainKeys("dispatchBacklog", "dispatchDead",
                "signalBacklog", "signalDead", "builtInConnectors");
        assertThat(details.get("checkedAt")).isInstanceOf(Instant.class);
    }
}
