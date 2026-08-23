package com.socp.platform.auth;

import com.socp.platform.error.ApiException;
import com.socp.platform.tenant.TenantContext;
import com.socp.platform.tenant.ServiceRequestSignature;
import com.nimbusds.jwt.JWTClaimsSet;
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

    @Test
    void validatedDefaultTenantCannotBeOverriddenByHeader() throws Exception {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject("user-1").claim("role", "analyst").claim("tenant", "default").build();
        org.mockito.BDDMockito.given(validator.isDevBypass()).willReturn(false);
        org.mockito.BDDMockito.given(validator.validate("signed-token")).willReturn(claims);
        org.mockito.BDDMockito.given(validator.extractTenant(claims)).willReturn("default");
        MockHttpServletRequest request = request("signed-token", "analyst", "attacker-tenant");

        assertTrue(interceptor.preHandle(request, new MockHttpServletResponse(), protectedHandler()));
        assertEquals("default", TenantContext.get());
    }

    @Test
    void signedServiceIdentityCanDelegateItsCurrentTenant() throws Exception {
        properties.setServiceSecret("a-long-shared-secret");
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject("svc-alert").claim("role", "viewer").claim("tenant", "default").build();
        org.mockito.BDDMockito.given(validator.isDevBypass()).willReturn(false);
        org.mockito.BDDMockito.given(validator.validate("service-token")).willReturn(claims);
        org.mockito.BDDMockito.given(validator.extractTenant(claims)).willReturn("default");
        MockHttpServletRequest request = request("service-token", "viewer", "tenant-b");
        request.setMethod("POST");
        request.setRequestURI("/api/v1/write");
        signService(request, "alert-web", "tenant-b", "nonce-1");

        assertTrue(interceptor.preHandle(request, new MockHttpServletResponse(), protectedHandler()));
        assertEquals("tenant-b", TenantContext.get());
    }

    @Test
    void serviceProofCannotBeReplayed() throws Exception {
        properties.setServiceSecret("a-long-shared-secret");
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject("svc-alert").claim("role", "analyst").claim("tenant", "default").build();
        org.mockito.BDDMockito.given(validator.isDevBypass()).willReturn(false);
        org.mockito.BDDMockito.given(validator.validate("service-token")).willReturn(claims);
        org.mockito.BDDMockito.given(validator.extractTenant(claims)).willReturn("default");
        MockHttpServletRequest request = request("service-token", "analyst", "tenant-b");
        request.setMethod("POST");
        request.setRequestURI("/api/v1/write");
        signService(request, "alert-web", "tenant-b", "reused-nonce");

        interceptor.preHandle(request, new MockHttpServletResponse(), protectedHandler());
        TenantContext.clear();
        ApiException replay = assertThrows(ApiException.class,
                () -> interceptor.preHandle(request, new MockHttpServletResponse(), protectedHandler()));
        assertEquals(401, replay.getCode());
    }

    @Test
    void staticIngestCredentialCannotSelectAnotherTenant() throws Exception {
        properties.setIngestToken("fixed-ingest-token");
        MockHttpServletRequest request = request("fixed-ingest-token", "analyst", "tenant-b");

        ApiException rejected = assertThrows(ApiException.class,
                () -> interceptor.preHandle(request, new MockHttpServletResponse(), protectedHandler()));

        assertEquals(401, rejected.getCode());
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

    private void signService(MockHttpServletRequest request, String service, String tenant, String nonce) {
        String timestamp = String.valueOf(java.time.Instant.now().getEpochSecond());
        String signature = ServiceRequestSignature.sign(properties.getServiceSecret(), service,
                request.getMethod(), request.getRequestURI(), tenant, timestamp, nonce);
        request.addHeader(ServiceRequestSignature.SERVICE_HEADER, service);
        request.addHeader(ServiceRequestSignature.TIMESTAMP_HEADER, timestamp);
        request.addHeader(ServiceRequestSignature.NONCE_HEADER, nonce);
        request.addHeader(ServiceRequestSignature.SIGNATURE_HEADER, signature);
    }

    static class ProtectedHandler {
        @RequireRole({"admin", "analyst"})
        public void write() {
        }
    }
}
