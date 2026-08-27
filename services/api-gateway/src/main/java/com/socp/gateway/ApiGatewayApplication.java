package com.socp.gateway;

import com.socp.platform.auth.config.SocpJwtConfig;
import com.socp.platform.auth.security.ProdGuard;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

/**
 * API 网关（SPIP15）：统一北向入口 :18092，路由到各业务服务 context-path。
 *
 * 注意这里只 @Import(SocpJwtConfig) 而不是像业务服务那样 scanBasePackages="com.socp.platform"：
 * 平台包里的 SocpAuthConfig / SocpRatelimitConfig 都实现了 WebMvcConfigurer，
 * 本模块已把 spring-boot-starter-web exclude 掉（WebFlux 不能和 MVC 共存），
 * 扫到它们会因为缺少 spring-webmvc 直接启动失败。JwtValidator 是零 Web 依赖的，可以安全复用。
 */
@SpringBootApplication
@Import({SocpJwtConfig.class, ProdGuard.class})
public class ApiGatewayApplication {
    public static void main(String[] args) {
        SpringApplication.run(ApiGatewayApplication.class, args);
    }
}
