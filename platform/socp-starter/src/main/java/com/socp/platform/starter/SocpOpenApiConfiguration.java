package com.socp.platform.starter;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Shared generated API metadata for servlet-side SOCP services.
 *
 * <p>The security requirements describe only real authentication boundaries.
 * Tenant binding is a property of the verified credential — the JWT
 * {@code tenant} claim for users, the registered credential for collectors, the
 * signature for internal service delegation — so {@code X-Tenant-Id} is
 * published as an informational scheme and never as a requirement: the gateway
 * overwrites the header with the claim value on every forwarded request.</p>
 */
@Configuration(proxyBeanMethods = false)
public class SocpOpenApiConfiguration {

    /** Wording the OpenAPI SDK gate and docs quote; keep the two in step. */
    static final String TENANT_HEADER_DESCRIPTION =
            "Informational only. Tenant binding comes from the verified credential "
                    + "(JWT tenant claim, collector credential, or service signature); a "
                    + "caller-supplied value is overwritten by the API gateway and contradicts "
                    + "a collector binding when it is sent directly to a service.";

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
                                .name("X-Tenant-Id")
                                .description(TENANT_HEADER_DESCRIPTION))
                        .addSecuritySchemes("cookieAuth", new SecurityScheme()
                                .type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.COOKIE)
                                .name("SOCP_SESSION")))
                // Browser clients authenticate with the HttpOnly session cookie;
                // service clients may use the JWT bearer scheme. Either way the
                // caller cannot choose its own tenant, so no header appears here.
                .addSecurityItem(new SecurityRequirement().addList("cookieAuth"))
                .addSecurityItem(new SecurityRequirement().addList("bearerAuth"));
    }
}
