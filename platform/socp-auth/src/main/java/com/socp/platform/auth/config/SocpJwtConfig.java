package com.socp.platform.auth.config;
import com.socp.platform.auth.security.JwtValidator;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * JwtValidator 装配（零 Web 依赖）。
 *
 * Servlet 服务通过 scanBasePackages 扫到 com.socp.platform 自动生效；
 * WebFlux 网关在 GatewayJwtConfiguration 中装配 JwtValidator，并仅引入无 Web 依赖的目录配置。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SocpSecurityProperties.class)
@Import(OperatorDirectoryConfiguration.class)
public class SocpJwtConfig {

    @Bean
    public JwtValidator jwtValidator(SocpSecurityProperties props) {
        return new JwtValidator(props);
    }
}
