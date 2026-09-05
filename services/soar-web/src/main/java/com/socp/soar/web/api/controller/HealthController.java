package com.socp.soar.web.api.controller;

import com.socp.soar.web.config.SoarRuntimeProperties;
import com.socp.soar.web.connector.SoarConnectorRegistry;
import com.socp.soar.web.persistence.repository.SoarDispatchOutboxRepository;
import com.socp.soar.web.persistence.repository.SoarSignalOutboxRepository;
import com.socp.soar.web.service.TemporalExecutor;
import com.socp.platform.error.api.ApiResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.health.HealthEndpoint;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** Runtime health endpoint for playbook execution and connector maturity. */
@RestController
public class HealthController {
    private final SoarRuntimeProperties properties;
    private final ObjectProvider<HealthEndpoint> healthEndpoint;
    private final ObjectProvider<TemporalExecutor> temporal;
    private final ObjectProvider<SoarDispatchOutboxRepository> dispatches;
    private final ObjectProvider<SoarSignalOutboxRepository> signals;
    private final ObjectProvider<SoarConnectorRegistry> connectors;

    public HealthController(SoarRuntimeProperties properties, ObjectProvider<HealthEndpoint> healthEndpoint) {
        this(properties, healthEndpoint, null, null, null, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public HealthController(SoarRuntimeProperties properties, ObjectProvider<HealthEndpoint> healthEndpoint,
                             ObjectProvider<TemporalExecutor> temporal,
                             ObjectProvider<SoarDispatchOutboxRepository> dispatches,
                             ObjectProvider<SoarSignalOutboxRepository> signals,
                             ObjectProvider<SoarConnectorRegistry> connectors) {
        this.properties = properties;
        this.healthEndpoint = healthEndpoint;
        this.temporal = temporal;
        this.dispatches = dispatches;
        this.signals = signals;
        this.connectors = connectors;
    }

    @GetMapping("/health")
    public ApiResult<Map<String, Object>> health() {
        HealthEndpoint endpoint = healthEndpoint.getIfAvailable();
        String platformStatus = endpoint == null ? "UP" : endpoint.health().getStatus().getCode();
        boolean temporalAvailable = temporal != null && temporal.getIfAvailable() != null
                && temporal.getIfAvailable().isAvailable();
        // The minimal constructor is used by legacy unit/integration probes;
        // in a real Spring context the provider is present and Temporal is a
        // required dependency for the execution health signal.
        String status = temporal == null
                ? platformStatus
                : ("UP".equalsIgnoreCase(platformStatus) && temporalAvailable ? "UP" : "DEGRADED");
        if (temporal == null) {
            return ApiResult.ok(Map.of("service", "soar-web", "status", status,
                    "maturity", properties.getMaturity()));
        }
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("service", "soar-web");
        details.put("status", status);
        details.put("platform", platformStatus);
        details.put("maturity", properties.getMaturity());
        details.put("temporal", Map.of("status", temporalAvailable ? "UP" : "UNAVAILABLE"));
        if (dispatches != null && dispatches.getIfAvailable() != null) {
            var repo = dispatches.getIfAvailable();
            details.put("dispatchBacklog", repo.countByStatus("PENDING"));
            details.put("dispatchDead", repo.countByStatus("DEAD"));
        }
        if (signals != null && signals.getIfAvailable() != null) {
            var repo = signals.getIfAvailable();
            details.put("signalBacklog", repo.countByStatus("PENDING"));
            details.put("signalDead", repo.countByStatus("DEAD"));
        }
        if (connectors != null && connectors.getIfAvailable() != null) {
            details.put("builtInConnectors", connectors.getIfAvailable().descriptors().stream()
                    .map(item -> item.id()).toList());
        }
        details.put("checkedAt", Instant.now());
        return ApiResult.ok(details);
    }
}
