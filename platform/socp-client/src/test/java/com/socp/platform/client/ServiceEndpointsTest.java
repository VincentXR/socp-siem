package com.socp.platform.client;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

class ServiceEndpointsTest {

    @Test
    void resolvesConfiguredAndDefaultUrlsWithContextPaths() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("socp.alert.url", " https://alert.example/ ");
        ServiceEndpoints endpoints = new ServiceEndpoints(environment);

        assertThat(endpoints.baseUrl(SocpService.ALERT)).isEqualTo("https://alert.example");
        assertThat(endpoints.url(SocpService.ALERT, "api/v1/alarms"))
                .isEqualTo("https://alert.example/alert-web/api/v1/alarms");
        assertThat(endpoints.url(SocpService.GATEWAY, "/auth/login"))
                .isEqualTo("http://localhost:18092/auth/login");
        assertThat(endpoints.url(SocpService.ALERT, "/alert-web/api/v1/alarms"))
                .isEqualTo("https://alert.example/alert-web/api/v1/alarms");
    }
}
