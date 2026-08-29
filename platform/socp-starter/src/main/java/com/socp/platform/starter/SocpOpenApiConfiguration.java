package com.socp.platform.starter;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Shared generated API metadata for servlet-side SOCP services. */
@Configuration(proxyBeanMethods = false)
public class SocpOpenApiConfiguration {

    @Bean
    OpenAPI socpOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("SOCP Security Operations API")
                        .description("Generated contract for the SOCP service boundary.")
                        .version("v1")
                        .contact(new Contact().name("SOCP Engineering")))
                .components(new Components()
                        .addSecuritySchemes("bearerAuth", new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT"))
                        .addSecuritySchemes("tenantHeader", new SecurityScheme()
                                .type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.HEADER)
                                .name("X-Tenant-Id")));
    }
}
