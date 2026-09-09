package com.socp.detect.web.api.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.actuate.health.HealthEndpoint;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HealthControllerTest {

    @Test
    void reportsServiceStatusWhenActuatorIsUnavailable() {
        ObjectProvider<HealthEndpoint> health = new DefaultListableBeanFactory().getBeanProvider(HealthEndpoint.class);

        var result = new HealthController(health).health();

        assertThat(result.data()).containsExactlyInAnyOrderEntriesOf(
                Map.of("service", "detect-web", "status", "UP"));
    }

    @Test
    void exposesDegradedDetectionRecoveryState() {
        ObjectProvider<HealthEndpoint> health = new DefaultListableBeanFactory().getBeanProvider(HealthEndpoint.class);
        var detection = mock(com.socp.detect.web.service.DetectEngineService.class);
        when(detection.isReady()).thenReturn(false);
        when(detection.recoveryStatus()).thenReturn(
                com.socp.detect.web.service.DetectEngineService.RecoveryStatus.DEGRADED);

        var result = new HealthController(health, detection).health();

        assertThat(result.data()).containsEntry("status", "DEGRADED")
                .containsEntry("detectionRecovery", "DEGRADED")
                .containsEntry("detectionReady", false);
    }
}
