package com.socp.gateway.security;

import com.socp.platform.auth.config.SocpSecurityProperties;
import com.socp.platform.auth.config.OperatorDirectoryConfiguration;
import com.socp.platform.auth.security.JwtValidator;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/** Configures gateway session verification without an HTTP call back into itself. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SocpSecurityProperties.class)
@Import(OperatorDirectoryConfiguration.class)
public class GatewayJwtConfiguration {

    @Bean
    public JwtValidator jwtValidator(SocpSecurityProperties properties, PlatformTokenIssuer tokenIssuer) {
        if (tokenIssuer.asymmetric()) {
            return new JwtValidator(properties, tokenIssuer.verificationJwkSet());
        }
        return new JwtValidator(properties);
    }
}
