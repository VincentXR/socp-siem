package com.socp.platform.starter;

import io.swagger.v3.oas.models.OpenAPI;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;

import static org.assertj.core.api.Assertions.assertThat;

class SocpOpenApiConfigurationTest {

    @Test
    void exposesVersionedSecuritySchemesForServletServices() {
        OpenAPI openApi = new SocpOpenApiConfiguration().socpOpenAPI();

        assertThat(openApi.getInfo().getTitle()).isEqualTo("SOCP Security Operations API");
        assertThat(openApi.getInfo().getVersion()).isEqualTo("v1");
        assertThat(openApi.getComponents().getSecuritySchemes())
                .containsKeys("bearerAuth", "tenantHeader");
    }

    @Test
    void starterIsRestrictedToServletApplications() {
        new SocpPlatformAutoConfiguration();
        ConditionalOnWebApplication condition =
                SocpPlatformAutoConfiguration.class.getAnnotation(ConditionalOnWebApplication.class);

        assertThat(condition).isNotNull();
        assertThat(condition.type()).isEqualTo(ConditionalOnWebApplication.Type.SERVLET);
    }
}
