package com.socp.gateway.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
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
                        .version("v1"));
    }
}
