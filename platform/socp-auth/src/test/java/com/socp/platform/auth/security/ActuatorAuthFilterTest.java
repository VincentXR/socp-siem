package com.socp.platform.auth.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.socp.platform.auth.config.SocpSecurityProperties;
import com.socp.platform.tenant.context.AuthenticatedIdentity;
import com.socp.platform.tenant.context.AuthenticatedIdentityContext;
import com.socp.platform.tenant.context.TenantContext;
import com.nimbusds.jwt.JWTClaimsSet;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/**
 * Locks the actuator boundary that the MVC interceptor cannot provide: the
 * filter must decide before handler mapping, and it must reuse the interceptor
 * credential rules instead of inventing a second authentication chain.
 */
class ActuatorAuthFilterTest {

    private final JwtValidator validator = mock(JwtValidator.class);
    private final SocpSecurityProperties properties = new SocpSecurityProperties();
    private MockFilterChain chain;

    @BeforeEach
    void configureCredentials() {
        properties.setMetricsToken("metrics-token");
        properties.setIngestToken("static-ingest-token");
        chain = new MockFilterChain();
    }

    @AfterEach
    void clearContext() {
        TenantContext.clear();
        AuthenticatedIdentityContext.clear();
    }

    @Test
    void runsAheadOfTheTenantFilter() {
        assertThat(ActuatorAuthFilter.ORDER).isLessThan(-200);
    }

    @Test
    void healthAndItsProbeGroupsStayPublic() throws Exception {
        for (String path : new String[]{"/actuator/health", "/actuator/health/liveness",
                "/actuator/health/readiness", "/actuator/health/"}) {
            MockHttpServletRequest request = actuatorRequest("GET", path, null);
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter().doFilter(request, response, chain);

            assertThat(response.getStatus()).isEqualTo(200);
            assertThat(chain.getRequest()).isNotNull();
            chain = new MockFilterChain();
        }
    }

    @Test
    void nonActuatorRequestsAreLeftToTheInterceptor() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/alarms");
        request.setMethod("GET");
        request.setRequestURI("/api/v1/alarms");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter().doFilter(request, response, chain);

        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    void unauthenticatedMetricsRequestIsRejectedWithTheUnifiedEnvelope() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter().doFilter(actuatorRequest("GET", "/actuator/metrics", null), response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(chain.getRequest()).isNull();
        assertThat(response.getContentAsString()).contains("\"code\":401");
    }

    @Test
    void metricsCredentialReadsPrometheusAndMetricsButNotTheEndpointIndex() throws Exception {
        MockHttpServletResponse prometheus = new MockHttpServletResponse();
        filter().doFilter(actuatorRequest("GET", "/actuator/prometheus", "metrics-token"), prometheus, chain);
        assertThat(prometheus.getStatus()).isEqualTo(200);
        assertThat(chain.getRequest()).isNotNull();

        chain = new MockFilterChain();
        MockHttpServletResponse info = new MockHttpServletResponse();
        filter().doFilter(actuatorRequest("GET", "/actuator/info", "metrics-token"), info, chain);
        assertThat(info.getStatus()).isEqualTo(403);
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void anyAuthenticatedUserRoleReachesMetricsByDocumentedDesign() throws Exception {
        // deploy/helm/README.md states the metrics credential is one accepted
        // credential on the metrics path, not an exclusive control: actuator
        // handlers carry no role annotation, so a viewer session also passes.
        given(validator.isDevBypass()).willReturn(false);
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject("viewer-1").claim("role", "viewer").claim("tenant", "tenant-a").build();
        given(validator.validate("viewer-token")).willReturn(claims);
        given(validator.extractTenant(claims)).willReturn("tenant-a");

        MockHttpServletResponse response = new MockHttpServletResponse();
        filter().doFilter(actuatorRequest("GET", "/actuator/metrics/jvm.memory.used", "viewer-token"),
                response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    void collectorCredentialsCannotReadRuntimeMetrics() throws Exception {
        properties.setCollectorCredentials("collector-a|tenant-a|collector-secret");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter().doFilter(actuatorRequest("GET", "/actuator/prometheus", "collector-secret"), response, chain);

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void contextPathPrefixedActuatorPathsAreProtected() throws Exception {
        MockHttpServletResponse unauthenticated = new MockHttpServletResponse();
        filter().doFilter(actuatorRequest("GET", "/actuator/prometheus", null, "/alert-web"),
                unauthenticated, chain);
        assertThat(unauthenticated.getStatus()).isEqualTo(401);
        assertThat(chain.getRequest()).isNull();

        chain = new MockFilterChain();
        MockHttpServletResponse withToken = new MockHttpServletResponse();
        filter().doFilter(actuatorRequest("GET", "/actuator/prometheus", "metrics-token", "/alert-web"),
                withToken, chain);
        assertThat(withToken.getStatus()).isEqualTo(200);
    }

    @Test
    void requestScopedIdentityIsClearedAfterTheEndpointRan() throws Exception {
        given(validator.isDevBypass()).willReturn(false);
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject("analyst-1").claim("role", "analyst").claim("tenant", "tenant-a").build();
        given(validator.validate("analyst-token")).willReturn(claims);
        given(validator.extractTenant(claims)).willReturn("tenant-a");

        filter().doFilter(actuatorRequest("GET", "/actuator/info", "analyst-token"),
                new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).isNotNull();
        // The filter clears the identity once the downstream call returns, so a
        // pooled servlet thread cannot leak it into the next request.
        assertThat(AuthenticatedIdentityContext.current()).isEmpty();
        assertThat(TenantContext.get()).isNull();
    }

    @Test
    void devBypassSessionsStillNeedABearerCredential() throws Exception {
        properties.setDevBypass(true);
        given(validator.isDevBypass()).willReturn(true);

        MockHttpServletResponse anonymous = new MockHttpServletResponse();
        filter().doFilter(actuatorRequest("GET", "/actuator/info", null), anonymous, chain);
        assertThat(anonymous.getStatus()).isEqualTo(401);

        chain = new MockFilterChain();
        MockHttpServletResponse dev = new MockHttpServletResponse();
        AtomicReference<AuthenticatedIdentity> duringDispatch = new AtomicReference<>();
        filter().doFilter(actuatorRequest("GET", "/actuator/info", "any-token"), dev,
                (request, response) -> {
                    duringDispatch.set(AuthenticatedIdentityContext.current().orElseThrow());
                    chain.doFilter(request, response);
                });
        assertThat(dev.getStatus()).isEqualTo(200);
        assertThat(duringDispatch.get().kind()).isEqualTo(AuthenticatedIdentity.Kind.DEV);
        assertThat(AuthenticatedIdentityContext.current()).isEmpty();
    }

    private ActuatorAuthFilter filter() {
        return new ActuatorAuthFilter(new AuthInterceptor(validator, properties), new ObjectMapper());
    }

    private static MockHttpServletRequest actuatorRequest(String method, String path, String token) {
        return actuatorRequest(method, path, token, "");
    }

    private static MockHttpServletRequest actuatorRequest(String method, String path, String token,
                                                         String contextPath) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, contextPath + path);
        request.setContextPath(contextPath == null ? "" : contextPath);
        request.setRequestURI(contextPath + path);
        if (token != null) request.addHeader("Authorization", "Bearer " + token);
        return request;
    }
}
