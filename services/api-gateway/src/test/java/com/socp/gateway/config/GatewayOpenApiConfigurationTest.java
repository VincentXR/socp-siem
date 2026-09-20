package com.socp.gateway.config;

import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GatewayOpenApiConfigurationTest {

    @Test
    void describesGatewayAuthenticationContract() {
        var openApi = new GatewayOpenApiConfiguration().gatewayOpenAPI();

        assertThat(openApi.getInfo().getTitle()).isEqualTo("SOCP API Gateway");
        assertThat(openApi.getInfo().getVersion()).isEqualTo("v1");
        assertThat(openApi.getComponents().getSecuritySchemes()).containsKeys("bearerAuth", "cookieAuth", "tenantHeader");
        assertThat(openApi.getComponents().getSecuritySchemes().get("cookieAuth").getName()).isEqualTo("SOCP_SESSION");
        assertThat(openApi.getComponents().getSecuritySchemes().get("tenantHeader").getDescription())
                .contains("Informational only", "verified JWT");
        assertThat(openApi.getSecurity()).allSatisfy(requirement -> assertThat(requirement).doesNotContainKey("tenantHeader"));
    }

    @Test
    void loginAndOidcDoNotRequireAnExistingSessionWhileSessionReadsInheritAuthentication() {
        var configuration = new GatewayOpenApiConfiguration();
        var api = configuration.gatewayOpenAPI();
        var login = new Operation();
        var oidc = new Operation();
        var session = new Operation();
        api.setPaths(new Paths().addPathItem("/auth/login", new PathItem().post(login))
                .addPathItem("/auth/oidc/callback", new PathItem().get(oidc))
                .addPathItem("/auth/session", new PathItem().get(session)));
        configuration.publicAuthenticationOperations().customise(api);
        assertThat(login.getSecurity()).isEmpty();
        assertThat(oidc.getSecurity()).isEmpty();
        assertThat(session.getSecurity()).isNull();
        assertThat(api.getSecurity()).hasSize(2);
    }
}
