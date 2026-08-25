package com.socp.platform.ratelimit.config;
import com.socp.platform.ratelimit.web.RateLimitInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** 注册限流拦截器，使其对带 @RateLimit 的接口生效（见 §3 / P1）。 */
@Configuration
public class SocpRatelimitConfig implements WebMvcConfigurer {
    private final RateLimitInterceptor interceptor;

    public SocpRatelimitConfig(RateLimitInterceptor interceptor) {
        this.interceptor = interceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(interceptor).addPathPatterns("/**").order(0);
    }
}
