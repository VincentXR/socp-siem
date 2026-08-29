package com.socp.ai.api.controller;

import com.socp.ai.config.AiRuntimeProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.actuate.health.HealthEndpoint;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class HealthControllerTest {

    @Test
    void reportsServiceStatusAndMaturityWhenActuatorIsUnavailable() {
        AiRuntimeProperties properties = new AiRuntimeProperties();
        properties.setMaturity("preview");
        ObjectProvider<HealthEndpoint> health = new DefaultListableBeanFactory().getBeanProvider(HealthEndpoint.class);

        var result = new HealthController(properties, health).health();

        assertThat(result.data()).containsExactlyInAnyOrderEntriesOf(
                Map.of("service", "ai-assistant", "status", "UP", "maturity", "preview"));
    }
}
