package com.socp.platform.auth;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * JwtValidator 装配（零 Web 依赖）。
 *
 * Servlet 服务通过 scanBasePackages 扫到 com.socp.platform 自动生效；
 * WebFlux 网关不扫该包（会拖进 Servlet 配置类），改用 @Import(SocpJwtConfig.class) 单点引入。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SocpSecurityProperties.class)
public class SocpJwtConfig {

    @Bean
    public JwtValidator jwtValidator(SocpSecurityProperties props) {
        return new JwtValidator(props);
    }
}
