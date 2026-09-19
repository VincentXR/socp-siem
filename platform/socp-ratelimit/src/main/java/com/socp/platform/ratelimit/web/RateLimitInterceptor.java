package com.socp.platform.ratelimit.web;
import com.socp.platform.ratelimit.api.RateLimit;
import com.socp.platform.ratelimit.store.RateLimitStore;
import com.socp.platform.error.exception.ApiException;
import com.socp.platform.tenant.context.TenantContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Applies the configured shared/local rate-limit store after authentication.
 *
 * <p>The store is a required collaborator rather than something defaulted to a
 * process-local map here: {@code InMemoryRateLimitStore} is a {@code !prod}
 * bean, so an unshared backend in production fails context startup instead of
 * silently localizing every replica's counters.</p>
 */
@Component
public class RateLimitInterceptor implements HandlerInterceptor {

    private final RateLimitStore store;

    @Autowired
    public RateLimitInterceptor(RateLimitStore store) {
        this.store = store;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod method)) return true;
        RateLimit limit = method.getMethodAnnotation(RateLimit.class);
        if (limit == null) return true;
        String tenant = TenantContext.require();
        String endpoint = method.getBeanType().getName() + '#' + method.getMethod().getName();
        RateLimitStore.Decision decision = store.acquire(
                "socp:ratelimit:" + tenant + ':' + endpoint, limit.permits(), limit.seconds());
        if (!decision.allowed()) {
            response.setHeader("Retry-After", String.valueOf(decision.retryAfterSeconds()));
            throw ApiException.tooManyRequests("Request rate limit exceeded", decision.retryAfterSeconds());
        }
        return true;
    }
}
