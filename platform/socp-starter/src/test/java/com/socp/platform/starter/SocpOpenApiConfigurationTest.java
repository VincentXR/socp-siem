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
                .containsKeys("bearerAuth", "tenantHeader", "cookieAuth");
        assertThat(openApi.getComponents().getSecuritySchemes().get("cookieAuth").getName())
                .isEqualTo("SOCP_SESSION");
        assertThat(openApi.getSecurity()).hasSize(2);
    }

    @Test
    void tenantHeaderIsDescribedButNeverRequired() {
        // The tenant comes from the verified credential, so a generated SDK must
        // not be able to select a tenant by sending a header.
        OpenAPI openApi = new SocpOpenApiConfiguration().socpOpenAPI();

        assertThat(openApi.getComponents().getSecuritySchemes().get("tenantHeader").getName())
                .isEqualTo("X-Tenant-Id");
        assertThat(openApi.getComponents().getSecuritySchemes().get("tenantHeader").getDescription())
                .contains("Informational only");
        assertThat(openApi.getSecurity())
                .allSatisfy(requirement -> assertThat(requirement.keySet())
                        .doesNotContain("tenantHeader")
                        .allMatch(scheme -> scheme.equals("cookieAuth") || scheme.equals("bearerAuth")));
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
