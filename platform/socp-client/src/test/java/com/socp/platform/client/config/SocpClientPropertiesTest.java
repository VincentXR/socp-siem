package com.socp.platform.client.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SocpClientPropertiesTest {

    @Test
    void exposesAndUpdatesAllTransportSettings() {
        SocpClientProperties properties = new SocpClientProperties();

        properties.setConnectTimeoutMs(2500);
        properties.setRequestTimeoutMs(4500);
        properties.setMaxAttempts(0);
        properties.setRetryBackoffMs(350);
        properties.setBodyLogLimit(500);
        properties.setUsername("service");
        properties.setPassword("secret");
        properties.setTokenTtlMs(60000);

        assertThat(properties.getConnectTimeoutMs()).isEqualTo(2500);
        assertThat(properties.getRequestTimeoutMs()).isEqualTo(4500);
        assertThat(properties.getMaxAttempts()).isEqualTo(1);
        assertThat(properties.getRetryBackoffMs()).isEqualTo(350);
        assertThat(properties.getBodyLogLimit()).isEqualTo(500);
        assertThat(properties.getUsername()).isEqualTo("service");
        assertThat(properties.getPassword()).isEqualTo("secret");
        assertThat(properties.getTokenTtlMs()).isEqualTo(60000);
    }
}
