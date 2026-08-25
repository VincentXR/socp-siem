package com.socp.platform.ratelimit.api;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** 接口级限流标记。默认 1 秒 10 次；生产用 Redisson 分布式令牌桶（见 RateLimitInterceptor）。 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RateLimit {
    int permits() default 10;

    int seconds() default 1;
}
