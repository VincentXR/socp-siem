package com.socp.gateway.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GatewayOpenApiConfigurationTest {

    @Test
    void describesGatewayAuthenticationContract() {
        var openApi = new GatewayOpenApiConfiguration().gatewayOpenAPI();

        assertThat(openApi.getInfo().getTitle()).isEqualTo("SOCP API Gateway");
        assertThat(openApi.getInfo().getVersion()).isEqualTo("v1");
    }
}
