package com.socp.platform.obs.health;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Fail-closed readiness contributor for infrastructure and synchronous
 * downstream services that are required by a workload.
 */
@Component("socpDependencies")
@ConditionalOnProperty(name = "socp.health.required-endpoints")
public class RequiredDependenciesHealthIndicator implements HealthIndicator {

    private final List<Endpoint> endpoints;
    private final int timeoutMs;

    public RequiredDependenciesHealthIndicator(
            @Value("${socp.health.required-endpoints}") String configuredEndpoints,
            @Value("${socp.health.connect-timeout-ms:500}") int timeoutMs) {
        this.endpoints = parse(configuredEndpoints);
        this.timeoutMs = Math.max(100, timeoutMs);
        if (endpoints.isEmpty()) {
            throw new IllegalArgumentException("socp.health.required-endpoints must not be empty");
        }
    }

    @Override
    public Health health() {
        Map<String, String> checks = new LinkedHashMap<>();
        boolean available = true;
        for (Endpoint endpoint : endpoints) {
            boolean reachable = reachable(endpoint);
            checks.put(endpoint.name(), reachable ? "UP" : "DOWN");
            available &= reachable;
        }
        Health.Builder result = available ? Health.up() : Health.down();
        return result.withDetail("checks", checks).build();
    }

    private boolean reachable(Endpoint endpoint) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(endpoint.host(), endpoint.port()), timeoutMs);
            return true;
        } catch (Exception unavailable) {
            return false;
        }
    }

    private static List<Endpoint> parse(String configuredEndpoints) {
        List<Endpoint> parsed = new ArrayList<>();
        for (String token : configuredEndpoints.split(",")) {
            String value = token.trim();
            if (value.isEmpty()) continue;
            int assignment = value.indexOf('=');
            int portSeparator = value.lastIndexOf(':');
            if (assignment <= 0 || portSeparator <= assignment + 1 || portSeparator == value.length() - 1) {
                throw new IllegalArgumentException("invalid required endpoint: " + value);
            }
            String name = value.substring(0, assignment).trim();
            String host = value.substring(assignment + 1, portSeparator).trim();
            int port;
            try {
                port = Integer.parseInt(value.substring(portSeparator + 1).trim());
            } catch (NumberFormatException invalidPort) {
                throw new IllegalArgumentException("invalid required endpoint port: " + value, invalidPort);
            }
            if (name.isEmpty() || host.isEmpty() || port < 1 || port > 65_535) {
                throw new IllegalArgumentException("invalid required endpoint: " + value);
            }
            parsed.add(new Endpoint(name, host, port));
        }
        return List.copyOf(parsed);
    }

    private record Endpoint(String name, String host, int port) {
    }
}
