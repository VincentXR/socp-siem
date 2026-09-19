package com.socp.soc.api.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.actuate.health.HealthEndpoint;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HealthControllerTest {

    @Test
    void reportsServiceStatusWhenActuatorIsUnavailable() {
        ObjectProvider<HealthEndpoint> health = new DefaultListableBeanFactory().getBeanProvider(HealthEndpoint.class);

        var result = new HealthController(health).health();

        assertThat(result.getStatusCodeValue()).isEqualTo(200);
        assertThat(result.getBody().data()).containsExactlyInAnyOrderEntriesOf(
                Map.of("service", "soc-base", "status", "UP"));
    }

    @Test
    void reportsServiceUnavailableWhenHealthIsNotUp() {
        HealthEndpoint endpoint = mock(HealthEndpoint.class, RETURNS_DEEP_STUBS);
        when(endpoint.health().getStatus().getCode()).thenReturn("DOWN");
        @SuppressWarnings("unchecked")
        ObjectProvider<HealthEndpoint> health = mock(ObjectProvider.class);
        when(health.getIfAvailable()).thenReturn(endpoint);

        var result = new HealthController(health).health();

        assertThat(result.getStatusCodeValue()).isEqualTo(503);
        assertThat(result.getBody().data()).containsEntry("status", "DOWN");
    }
}
