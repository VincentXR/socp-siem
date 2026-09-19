package com.socp.platform.auth.security;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.ComponentScan;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Routing evidence for the actuator boundary (2026-09 review). Actuator
 * endpoints are answered by Spring Boot's own handler mapping, which the MVC
 * interceptor registered by {@code SocpAuthConfig} never sees, so this test
 * boots a real servlet container with the service management exposure set and
 * drives HTTP through the filter chain instead of faking a HandlerMethod.
 */
@SpringBootTest(
        classes = ActuatorEndpointRoutingTest.Application.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.main.banner-mode=off",
                "server.servlet.context-path=/demo-web",
                "management.endpoints.web.exposure.include=health,info,metrics,prometheus",
                "management.endpoint.health.probes.enabled=true",
                "socp.security.jwt-secret=" + ActuatorEndpointRoutingTest.JWT_SECRET,
                "socp.security.audience=socp-api",
                "socp.security.metrics-token=test-metrics-token"
        })
class ActuatorEndpointRoutingTest {

    static final String JWT_SECRET = "socp-actuator-routing-test-secret-0123456789abcdef";

    @SpringBootConfiguration
    @EnableAutoConfiguration(exclude = DataSourceAutoConfiguration.class)
    @ComponentScan(basePackages = "com.socp.platform.auth")
    static class Application {
    }

    @LocalServerPort
    private int port;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @Test
    void healthRemainsPublicWhileEveryOtherEndpointNeedsCredentials() throws Exception {
        assertThat(get("/actuator/health", null).statusCode()).isEqualTo(200);
        assertThat(get("/actuator/health/liveness", null).statusCode()).isEqualTo(200);

        HttpResponse<String> metrics = get("/actuator/metrics", null);
        // 401 with the platform envelope, not an actuator 404: the endpoint is
        // really mapped and the filter rejected the caller before dispatch.
        assertThat(metrics.statusCode()).isEqualTo(401);
        assertThat(metrics.body()).contains("\"code\":401");

        assertThat(get("/actuator/metrics/jvm.memory.used", null).statusCode()).isEqualTo(401);
        assertThat(get("/actuator/info", null).statusCode()).isEqualTo(401);
        assertThat(get("/actuator", null).statusCode()).isEqualTo(401);
    }

    @Test
    void metricsCredentialReadsMetricsButNotOtherEndpoints() throws Exception {
        HttpResponse<String> metrics = get("/actuator/metrics", "Bearer test-metrics-token");
        assertThat(metrics.statusCode()).isEqualTo(200);
        assertThat(metrics.body()).contains("names");
        assertThat(get("/actuator/metrics/jvm.memory.used", "Bearer test-metrics-token").statusCode())
                .isEqualTo(200);

        assertThat(get("/actuator/info", "Bearer test-metrics-token").statusCode()).isEqualTo(403);
        assertThat(get("/actuator", "Bearer test-metrics-token").statusCode()).isEqualTo(403);
    }

    @Test
    void prometheusPathIsGuardedByTheSameRuleAsMetrics() throws Exception {
        // The scrape endpoint is only mapped when the Prometheus exporter is
        // enabled, which the repository's management configuration does not do;
        // the boundary must not be what stops a holder of the metrics token.
        assertThat(get("/actuator/prometheus", null).statusCode()).isEqualTo(401);
        int authorized = get("/actuator/prometheus", "Bearer test-metrics-token").statusCode();
        assertThat(authorized).isNotEqualTo(401);
        assertThat(authorized).isNotEqualTo(403);
    }

    @Test
    void verifiedUserSessionReadsMetricsThroughTheSameRuleAsTheInterceptor() throws Exception {
        String token = session("analyst", "tenant-a");

        HttpResponse<String> response = get("/actuator/metrics", "Bearer " + token);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("names");
        // Unannotated actuator endpoints accept any authenticated identity by
        // documented design (deploy/helm/README.md), so a viewer session passes.
        assertThat(get("/actuator/metrics", "Bearer " + session("viewer", "tenant-a")).statusCode())
                .isEqualTo(200);
    }

    @Test
    void forgedUnparsableAndNonBearerCredentialsAreRejected() throws Exception {
        assertThat(get("/actuator/metrics", "Bearer not-a-signed-token").statusCode()).isEqualTo(401);
        assertThat(get("/actuator/metrics", "Basic dXNlcjpwYXNz").statusCode()).isEqualTo(401);
    }

    private HttpResponse<String> get(String path, String authorization) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + port + "/demo-web" + path)).GET();
        if (authorization != null) builder.header("Authorization", authorization);
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String session(String role, String tenant) throws Exception {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject("routing-" + role)
                .jwtID(UUID.randomUUID().toString())
                .audience("socp-api")
                .claim("role", role)
                .claim("tenant", tenant)
                .issueTime(Date.from(Instant.now().minusSeconds(5)))
                .expirationTime(Date.from(Instant.now().plusSeconds(300)))
                .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
        jwt.sign(new MACSigner(JWT_SECRET.getBytes(StandardCharsets.UTF_8)));
        return jwt.serialize();
    }
}
