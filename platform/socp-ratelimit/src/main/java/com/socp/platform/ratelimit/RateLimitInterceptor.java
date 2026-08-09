package com.socp.platform.ratelimit;

import com.socp.platform.error.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 限流拦截器：对带 @RateLimit 的接口做令牌桶限流。key 含租户，实现“每租户独立配额”。
 * 生产环境把 TokenBucket 换成 Redisson 分布式实现（见架构 §3 / P1：替换手写旧锁为 Redisson）。
 */
@Component
public class RateLimitInterceptor implements HandlerInterceptor {
    private final ConcurrentHashMap<String, TokenBucket> buckets = new ConcurrentHashMap<>();

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) {
        if (!(handler instanceof HandlerMethod hm)) return true;
        RateLimit rl = hm.getMethodAnnotation(RateLimit.class);
        if (rl == null) return true;
        String key = req.getHeader("X-Tenant-Id") + ":" + hm.getMethod().toGenericString();
        TokenBucket bucket = buckets.computeIfAbsent(key, k -> new TokenBucket(rl.permits(), rl.seconds()));
        if (!bucket.tryAcquire()) {
            throw ApiException.tooManyRequests("请求过于频繁，请稍后重试", bucket.retryAfterSeconds());
        }
        return true;
    }
}
