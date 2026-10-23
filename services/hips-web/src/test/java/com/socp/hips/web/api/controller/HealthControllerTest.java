package com.socp.hips.web.api.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint;
import org.springframework.boot.health.actuate.endpoint.IndicatedHealthDescriptor;
import org.springframework.boot.health.contributor.Health;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HealthControllerTest {

    @Test
    void reportsServiceStatusWhenActuatorIsUnavailable() {
        ObjectProvider<HealthEndpoint> health = new DefaultListableBeanFactory().getBeanProvider(HealthEndpoint.class);

        var result = new HealthController(health).health();

        assertThat(result.getStatusCode().value()).isEqualTo(200);
        assertThat(result.getBody().data()).containsExactlyInAnyOrderEntriesOf(
                Map.of("service", "hips-web", "status", "UP"));
    }

    @Test
    void reportsServiceUnavailableWhenHealthIsNotUp() {
        HealthEndpoint endpoint = mock(HealthEndpoint.class);
        IndicatedHealthDescriptor descriptor = mock(IndicatedHealthDescriptor.class);
        when(descriptor.getStatus()).thenReturn(Health.down().build().getStatus());
        when(endpoint.health()).thenReturn(descriptor);
        @SuppressWarnings("unchecked")
        ObjectProvider<HealthEndpoint> health = mock(ObjectProvider.class);
        when(health.getIfAvailable()).thenReturn(endpoint);

        var result = new HealthController(health).health();

        assertThat(result.getStatusCode().value()).isEqualTo(503);
        assertThat(result.getBody().data()).containsEntry("status", "DOWN");
    }
}
