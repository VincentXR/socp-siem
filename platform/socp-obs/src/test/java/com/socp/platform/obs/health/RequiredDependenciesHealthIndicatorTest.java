package com.socp.platform.obs.health;

import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Status;

import java.net.ServerSocket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RequiredDependenciesHealthIndicatorTest {

    @Test
    void reportsUpOnlyWhenEveryRequiredEndpointIsReachable() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            var indicator = new RequiredDependenciesHealthIndicator(
                    "test=127.0.0.1:" + server.getLocalPort(), 200);
            assertEquals(Status.UP, indicator.health().getStatus());
        }
    }

    @Test
    void reportsDownWhenARequiredEndpointIsUnavailable() throws Exception {
        int closedPort;
        try (ServerSocket server = new ServerSocket(0)) {
            closedPort = server.getLocalPort();
        }
        var indicator = new RequiredDependenciesHealthIndicator(
                "unavailable=127.0.0.1:" + closedPort, 200);
        assertEquals(Status.DOWN, indicator.health().getStatus());
    }

    @Test
    void rejectsMalformedConfigurationAtStartup() {
        assertThrows(IllegalArgumentException.class,
                () -> new RequiredDependenciesHealthIndicator("kafka=missing-port", 200));
    }
}
