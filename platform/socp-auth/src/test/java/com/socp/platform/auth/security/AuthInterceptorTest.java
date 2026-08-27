package com.socp.platform.auth.security;
import com.socp.platform.auth.config.SocpSecurityProperties;
import com.socp.platform.error.exception.ApiException;
import com.socp.platform.tenant.context.TenantContext;
import com.socp.platform.tenant.security.ServiceRequestSignature;
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
        request.setMethod("POST");
        request.setRequestURI("/search-config/api/v1/ingest");

        ApiException rejected = assertThrows(ApiException.class,
                () -> interceptor.preHandle(request, new MockHttpServletResponse(), protectedHandler()));

        assertEquals(401, rejected.getCode());
    }

    @Test
    void staticIngestCredentialIsLimitedToTheIngestEndpoint() throws Exception {
        properties.setIngestToken("fixed-ingest-token");
        MockHttpServletRequest request = request("fixed-ingest-token", null, null);
        request.setMethod("POST");
        request.setRequestURI("/search-config/api/v1/ingest");

        assertTrue(interceptor.preHandle(request, new MockHttpServletResponse(), ingestHandler()));
        assertEquals("default", TenantContext.get());
    }

    @Test
    void registeredCollectorCredentialBindsIdentityAndTenant() throws Exception {
        properties.setCollectorCredentials("collector-a|tenant-a|collector-secret");
        MockHttpServletRequest request = request("collector-secret", null, "tenant-a");
        request.setMethod("POST");
        request.setRequestURI("/search-config/api/v1/ingest");
        request.addHeader("X-SOCP-Collector", "collector-a");

        assertTrue(interceptor.preHandle(request, new MockHttpServletResponse(), ingestHandler()));
        assertEquals("tenant-a", TenantContext.get());
        assertEquals("collector-a", request.getAttribute(CollectorCredentialRegistry.COLLECTOR_ID_ATTRIBUTE));
    }

    @Test
    void registeredCollectorCredentialCannotClaimAnotherCollectorOrTenant() throws Exception {
        properties.setCollectorCredentials("collector-a|tenant-a|collector-secret");
        MockHttpServletRequest request = request("collector-secret", null, "tenant-b");
        request.setMethod("POST");
        request.setRequestURI("/search-config/api/v1/ingest");
        request.addHeader("X-SOCP-Collector", "collector-b");

        ApiException rejected = assertThrows(ApiException.class,
                () -> interceptor.preHandle(request, new MockHttpServletResponse(), ingestHandler()));
        assertEquals(401, rejected.getCode());
    }

    @Test
    void serviceOnlyEndpointRejectsAnalystJwtWithoutServiceProof() throws Exception {
        properties.setDevBypass(true);
        org.mockito.BDDMockito.given(validator.isDevBypass()).willReturn(true);
        MockHttpServletRequest request = request("analyst-token", "analyst", "tenant-a");

        ApiException rejected = assertThrows(ApiException.class,
                () -> interceptor.preHandle(request, new MockHttpServletResponse(), serviceHandler()));
        assertEquals(403, rejected.getCode());
    }

    @Test
    void serviceOnlyEndpointAcceptsSignedServiceProof() throws Exception {
        properties.setServiceSecret("a-long-shared-secret");
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject("svc-alert").claim("role", "analyst").claim("tenant", "default").build();
        org.mockito.BDDMockito.given(validator.isDevBypass()).willReturn(false);
        org.mockito.BDDMockito.given(validator.validate("service-token")).willReturn(claims);
        org.mockito.BDDMockito.given(validator.extractTenant(claims)).willReturn("default");
        MockHttpServletRequest request = request("service-token", "analyst", "tenant-a");
        request.setMethod("POST");
        request.setRequestURI("/notify-web/api/v1/notify/alert");
        signService(request, "alert-web", "tenant-a", "service-only-nonce");

        assertTrue(interceptor.preHandle(request, new MockHttpServletResponse(), serviceHandler()));
        assertEquals("alert-web", request.getAttribute(RequireService.SERVICE_ID_ATTRIBUTE));
    }

    @Test
    void metricsCredentialIsLimitedToReadOnlyMetrics() throws Exception {
        properties.setMetricsToken("metrics-token");
        MockHttpServletRequest request = request("metrics-token", null, null);
        request.setMethod("GET");
        request.setRequestURI("/alert-web/actuator/prometheus");

        assertTrue(interceptor.preHandle(request, new MockHttpServletResponse(), metricsHandler()));
        assertEquals("default", TenantContext.get());

        MockHttpServletRequest rejectedRequest = request("metrics-token", null, null);
        rejectedRequest.setMethod("POST");
        rejectedRequest.setRequestURI("/alert-web/api/v1/alarms");
        ApiException rejected = assertThrows(ApiException.class,
                () -> interceptor.preHandle(rejectedRequest, new MockHttpServletResponse(), metricsHandler()));
        assertEquals(403, rejected.getCode());
    }

    private static MockHttpServletRequest request(String token, String role, String tenant) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + token);
        if (role != null) request.addHeader("X-Role", role);
        if (tenant != null) request.addHeader("X-Tenant-Id", tenant);
        return request;
    }

    private static HandlerMethod protectedHandler() throws NoSuchMethodException {
        Method method = ProtectedHandler.class.getMethod("write");
        return new HandlerMethod(new ProtectedHandler(), method);
    }

    private static HandlerMethod ingestHandler() throws NoSuchMethodException {
        Method method = ProtectedHandler.class.getMethod("ingest");
        return new HandlerMethod(new ProtectedHandler(), method);
    }

    private static HandlerMethod metricsHandler() throws NoSuchMethodException {
        Method method = ProtectedHandler.class.getMethod("metrics");
        return new HandlerMethod(new ProtectedHandler(), method);
    }

    private static HandlerMethod serviceHandler() throws NoSuchMethodException {
        Method method = ProtectedHandler.class.getMethod("serviceOnly");
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

        public void ingest() {
        }

        public void metrics() {
        }

        @RequireService
        public void serviceOnly() {
        }
    }
}
