package com.socp.platform.auth;

import com.socp.platform.error.ApiException;
import com.socp.platform.tenant.TenantContext;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class AuthInterceptorTest {

    private final JwtValidator validator = mock(JwtValidator.class);
    private final SocpSecurityProperties properties = new SocpSecurityProperties();
    private final AuthInterceptor interceptor = new AuthInterceptor(validator, properties);

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void viewerIsRejectedByMethodRoleRequirement() throws Exception {
        properties.setDevBypass(true);
        org.mockito.BDDMockito.given(validator.isDevBypass()).willReturn(true);
        MockHttpServletRequest request = request("viewer-token", "viewer", null);

        ApiException error = assertThrows(ApiException.class,
                () -> interceptor.preHandle(request, new MockHttpServletResponse(), protectedHandler()));

        assertEquals(403, error.getCode());
    }

    @Test
    void analystRoleCanPassAndTenantHeaderIsBound() throws Exception {
        properties.setDevBypass(true);
        org.mockito.BDDMockito.given(validator.isDevBypass()).willReturn(true);
        MockHttpServletRequest request = request("analyst-token", "analyst", "tenant-a");

        assertTrue(interceptor.preHandle(request, new MockHttpServletResponse(), protectedHandler()));
        assertEquals("tenant-a", TenantContext.get());
    }

    private static MockHttpServletRequest request(String token, String role, String tenant) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + token);
        request.addHeader("X-Role", role);
        if (tenant != null) request.addHeader("X-Tenant-Id", tenant);
        return request;
    }

    private static HandlerMethod protectedHandler() throws NoSuchMethodException {
        Method method = ProtectedHandler.class.getMethod("write");
        return new HandlerMethod(new ProtectedHandler(), method);
    }

    static class ProtectedHandler {
        @RequireRole({"admin", "analyst"})
        public void write() {
        }
    }
}
