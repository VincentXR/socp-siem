package com.socp.gateway;

import com.socp.platform.auth.security.ProdGuard;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

/**
 * API 网关（SPIP15）：统一北向入口 :18092，路由到各业务服务 context-path。
 *
 * 注意这里只显式引入 ProdGuard，而不是 servlet 侧 socp-starter：
 * 平台包里的 SocpAuthConfig / SocpRatelimitConfig 都实现了 WebMvcConfigurer，
 * 本模块已把 spring-boot-starter-web exclude 掉（WebFlux 不能和 MVC 共存），
 * 扫到它们会因为缺少 spring-webmvc 直接启动失败。GatewayJwtConfiguration
 * 在网关包内装配复用的 JwtValidator，避免通过 HTTP 回调自身 JWKS。
 */
@SpringBootApplication
@Import(ProdGuard.class)
public class ApiGatewayApplication {
    public static void main(String[] args) {
        SpringApplication.run(ApiGatewayApplication.class, args);
    }
}
