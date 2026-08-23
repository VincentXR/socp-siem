package com.socp.platform.auth;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 注册鉴权拦截器，跳过 actuator / 健康检查等无需鉴权的路径。
 * 业务服务把 com.socp.platform 纳入 scanBasePackages 即可启用机机/人机鉴权（见 §3 横切机制）。
 *
 * 注意：AuthInterceptor 需要注入 JwtValidator，不能再 new 出来，必须用容器里的 Bean。
 */
@Configuration
@Import(SocpJwtConfig.class)
public class SocpAuthConfig implements WebMvcConfigurer {

    private final AuthInterceptor authInterceptor;

    public SocpAuthConfig(AuthInterceptor authInterceptor) {
        this.authInterceptor = authInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authInterceptor)
                .addPathPatterns("/**")
                .excludePathPatterns("/actuator/**", "/health", "/error")
                .order(-100);
    }
}
