package com.socp.gateway.filter;

import com.nimbusds.jwt.JWTClaimsSet;
import com.socp.platform.auth.security.InMemoryServiceNonceStore;
import com.socp.platform.auth.security.JwtValidator;
import com.socp.platform.auth.security.ServiceNonceStore;
import com.socp.platform.tenant.security.ServiceRequestSignature;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.PrematureCloseException;

import java.time.Duration;
import java.util.ArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GatewayRetryAuthenticationTest {
    private static final String SECRET = "retry-test-service-secret-0123456789";
    private static final String PATH = "/report-web/api/v1/reports/archive";

    @Test
    void retryAfterUpstreamConsumedProofUsesFreshNonceAndPreservesVerifiedIdentity() {
        var validator = mock(JwtValidator.class);
        var claims = new JWTClaimsSet.Builder().subject("reader").claim("role", "analyst")
                .claim("tenant", "tenant-a").build();
        when(validator.validate("session-token")).thenReturn(claims);
        when(validator.extractTenant(claims)).thenReturn("tenant-a");
        var authentication = new GatewayFilter(validator, "http://localhost:5173");
        var signing = new GatewayRequestSigningFilter(SECRET);
        var retry = new ReportArchiveReadRetryGatewayFilterFactory()
                .apply(new ReportArchiveReadRetryGatewayFilterFactory.Config());
        var nonces = new InMemoryServiceNonceStore();
        var attempts = new ArrayList<HttpHeaders>();
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.method(org.springframework.http.HttpMethod.GET,
                        java.net.URI.create(PATH + "?prefix=reports%2F"))
                .header(HttpHeaders.AUTHORIZATION, "Bearer session-token")
                .header("X-Tenant-Id", "untrusted-tenant")
                .header(ServiceRequestSignature.GATEWAY_NONCE_HEADER, "untrusted-nonce"));

        authentication.filter(exchange, verified -> retry.filter(verified, attempt -> signing.filter(attempt, upstream -> {
            var headers = new HttpHeaders();
            headers.addAll(upstream.getRequest().getHeaders());
            attempts.add(headers);
            String nonce = headers.getFirst(ServiceRequestSignature.GATEWAY_NONCE_HEADER);
            assertThat(ServiceRequestSignature.verify(SECRET,
                    headers.getFirst(ServiceRequestSignature.GATEWAY_SIGNATURE_HEADER),
                    ServiceRequestSignature.GATEWAY_SERVICE, "GET",
                    ServiceRequestSignature.gatewayBinding(PATH, "session-token"), "tenant-a",
                    headers.getFirst(ServiceRequestSignature.GATEWAY_TIMESTAMP_HEADER), nonce)).isTrue();
            var claim = nonces.claim(ServiceRequestSignature.GATEWAY_SERVICE, nonce, Duration.ofMinutes(2));
            if (claim == ServiceNonceStore.ClaimResult.REPLAYED) {
                upstream.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
                return upstream.getResponse().setComplete();
            }
            // The upstream has authenticated and consumed the proof, then its
            // connection disappears before any response headers are received.
            if (attempts.size() == 1) return Mono.error(PrematureCloseException.TEST_EXCEPTION);
            assertThat(upstream.getRequest().getURI().getRawQuery()).isEqualTo("prefix=reports%2F");
            upstream.getResponse().setStatusCode(HttpStatus.OK);
            return upstream.getResponse().setComplete();
        }))).block(Duration.ofSeconds(3));

        assertThat(attempts).hasSize(2);
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(attempts.get(0).getFirst(ServiceRequestSignature.GATEWAY_NONCE_HEADER))
                .isNotEqualTo(attempts.get(1).getFirst(ServiceRequestSignature.GATEWAY_NONCE_HEADER));
        for (var headers : attempts) {
            assertThat(headers.getFirst("X-Tenant-Id")).isEqualTo("tenant-a");
            assertThat(headers.getFirst("X-Socp-User")).isEqualTo("reader");
            assertThat(headers.getFirst(HttpHeaders.AUTHORIZATION)).isEqualTo("Bearer session-token");
        }
    }
}
