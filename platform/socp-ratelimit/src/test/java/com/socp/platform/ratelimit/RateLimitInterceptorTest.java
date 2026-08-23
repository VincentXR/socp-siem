package com.socp.platform.ratelimit;

import com.socp.platform.error.ApiException;
import com.socp.platform.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RateLimitInterceptorTest {

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void quotaIsKeyedByAuthenticatedTenantContext() throws Exception {
        RateLimitInterceptor interceptor = new RateLimitInterceptor(new InMemoryRateLimitStore());
        HandlerMethod handler = handler();
        TenantContext.set("tenant-a");
        assertTrue(interceptor.preHandle(new MockHttpServletRequest(), new MockHttpServletResponse(), handler));
        ApiException rejected = assertThrows(ApiException.class,
                () -> interceptor.preHandle(new MockHttpServletRequest(), new MockHttpServletResponse(), handler));
        assertEquals(429, rejected.getCode());

        TenantContext.set("tenant-b");
        assertTrue(interceptor.preHandle(new MockHttpServletRequest(), new MockHttpServletResponse(), handler));
    }

    private static HandlerMethod handler() throws NoSuchMethodException {
        Method method = LimitedHandler.class.getMethod("read");
        return new HandlerMethod(new LimitedHandler(), method);
    }

    static class LimitedHandler {
        @RateLimit(permits = 1, seconds = 60)
        public void read() {
        }
    }
}
