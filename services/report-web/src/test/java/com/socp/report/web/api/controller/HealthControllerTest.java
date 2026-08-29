package com.socp.report.web.api.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.actuate.health.HealthEndpoint;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class HealthControllerTest {

    @Test
    void reportsServiceStatusWhenActuatorIsUnavailable() {
        ObjectProvider<HealthEndpoint> health = new DefaultListableBeanFactory().getBeanProvider(HealthEndpoint.class);

        var result = new HealthController(health).health();

        assertThat(result.data()).containsExactlyInAnyOrderEntriesOf(
                Map.of("service", "report-web", "status", "UP"));
    }
}
