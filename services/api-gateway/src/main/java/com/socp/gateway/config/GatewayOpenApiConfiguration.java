package com.socp.gateway.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** OpenAPI metadata for the reactive gateway endpoints. */
@Configuration(proxyBeanMethods = false)
public class GatewayOpenApiConfiguration {

    @Bean
    OpenAPI gatewayOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("SOCP API Gateway")
                        .description("Authentication and routing contract for the SOCP gateway.")
                        .version("v1"))
                .components(new Components()
                        .addSecuritySchemes("bearerAuth", new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT"))
                        .addSecuritySchemes("cookieAuth", new SecurityScheme()
                                .type(SecurityScheme.Type.APIKEY).in(SecurityScheme.In.COOKIE).name("SOCP_SESSION"))
                        .addSecuritySchemes("tenantHeader", new SecurityScheme()
                                .type(SecurityScheme.Type.APIKEY).in(SecurityScheme.In.HEADER).name("X-Tenant-Id")
                                .description("Informational only. The gateway binds the tenant to the verified "
                                        + "JWT claim and overwrites caller-supplied tenant headers.")))
                .addSecurityItem(new SecurityRequirement().addList("cookieAuth"))
                .addSecurityItem(new SecurityRequirement().addList("bearerAuth"));
    }

    @Bean
    OpenApiCustomizer publicAuthenticationOperations() {
        return api -> {
            if (api.getPaths() == null) return;
            api.getPaths().forEach((path, item) -> {
                if ("/auth/login".equals(path) || "/auth/service-token".equals(path)
                        || path.startsWith("/auth/oidc/")) {
                    item.readOperations().forEach(operation -> operation.setSecurity(java.util.List.of()));
                }
            });
        };
    }
}
