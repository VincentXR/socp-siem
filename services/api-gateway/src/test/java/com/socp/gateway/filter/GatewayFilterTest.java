package com.socp.gateway.filter;

import com.socp.gateway.api.controller.AuthController;
import com.nimbusds.jwt.JWTClaimsSet;
import com.socp.platform.auth.security.JwtValidator;
import com.socp.platform.tenant.security.ServiceRequestSignature;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpCookie;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class GatewayFilterTest {

    @Mock
    private JwtValidator jwtValidator;

    @Mock
    private GatewayFilterChain chain;

    @Test
    void rejectsRequestsWithoutBearerToken() {
        GatewayFilter filter = new GatewayFilter(jwtValidator);
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/alarms").header("X-Trace-Id", "trace-missing").build());

        filter.filter(exchange, chain).block(Duration.ofSeconds(1));

        assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());
        verifyNoInteractions(chain);
    }

    @Test
    void viewerCannotWriteEvenWithValidToken() {
        GatewayFilter filter = new GatewayFilter(jwtValidator);
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject("viewer-user")
                .claim("role", "viewer")
                .claim("tenant", "tenant-a")
                .build();
        given(jwtValidator.isDevBypass()).willReturn(false);
        given(jwtValidator.validate("viewer-token")).willReturn(claims);
        given(jwtValidator.extractTenant(claims)).willReturn("tenant-a");
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/v1/alarms")
                        .header("Authorization", "Bearer viewer-token")
                        .build());

        filter.filter(exchange, chain).block(Duration.ofSeconds(1));

        assertEquals(HttpStatus.FORBIDDEN, exchange.getResponse().getStatusCode());
        verifyNoInteractions(chain);
    }

    @Test
    void forwardsValidatedTenantAndTraceHeaders() {
        GatewayFilter filter = new GatewayFilter(jwtValidator);
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject("analyst-user")
                .claim("role", "analyst")
                .claim("tenant", "tenant-a")
                .claim("locale", "en-US")
                .build();
        given(jwtValidator.isDevBypass()).willReturn(false);
        given(jwtValidator.validate("analyst-token")).willReturn(claims);
        given(jwtValidator.extractTenant(claims)).willReturn("tenant-a");
        given(chain.filter(org.mockito.ArgumentMatchers.any())).willReturn(Mono.empty());
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/alarms")
                        .header("Authorization", "Bearer analyst-token")
                        .header("X-Tenant-Id", "attacker-tenant")
                        .header("X-Trace-Id", "trace-forwarded")
                        .header(ServiceRequestSignature.SERVICE_HEADER, "forged-service")
                        .header(ServiceRequestSignature.SIGNATURE_HEADER, "forged-signature")
                        .build());

        filter.filter(exchange, chain).block(Duration.ofSeconds(1));

        ArgumentCaptor<ServerWebExchange> forwarded = ArgumentCaptor.forClass(ServerWebExchange.class);
        verify(chain).filter(forwarded.capture());
        assertEquals("tenant-a", forwarded.getValue().getRequest().getHeaders().getFirst("X-Tenant-Id"));
        assertEquals("trace-forwarded", forwarded.getValue().getRequest().getHeaders().getFirst("X-Trace-Id"));
        assertEquals("Bearer analyst-token",
                forwarded.getValue().getRequest().getHeaders().getFirst("Authorization"));
        assertEquals("analyst-user",
                forwarded.getValue().getRequest().getHeaders().getFirst("X-Socp-User"));
        assertEquals("en-US",
                forwarded.getValue().getRequest().getHeaders().getFirst("X-Socp-Locale"));
        assertEquals(null, forwarded.getValue().getRequest().getHeaders()
                .getFirst(ServiceRequestSignature.SERVICE_HEADER));
        assertEquals(null, forwarded.getValue().getRequest().getHeaders()
                .getFirst(ServiceRequestSignature.SIGNATURE_HEADER));
    }

    @Test
    void rejectsCrossSiteMutationWhenSessionComesFromCookie() {
        GatewayFilter filter = new GatewayFilter(jwtValidator);
        JWTClaimsSet claims = new JWTClaimsSet.Builder().subject("analyst-user")
                .claim("role", "analyst").claim("tenant", "tenant-a").build();
        given(jwtValidator.validate("cookie-token")).willReturn(claims);
        given(jwtValidator.extractTenant(claims)).willReturn("tenant-a");
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/v1/alarms")
                        .cookie(new HttpCookie(AuthController.SESSION_COOKIE, "cookie-token"))
                        .header("Origin", "https://evil.example")
                        .build());

        filter.filter(exchange, chain).block(Duration.ofSeconds(1));

        assertEquals(HttpStatus.FORBIDDEN, exchange.getResponse().getStatusCode());
        verifyNoInteractions(chain);
    }

    @Test
    void acceptsAllowedOriginForCookieMutation() {
        GatewayFilter filter = new GatewayFilter(jwtValidator);
        JWTClaimsSet claims = new JWTClaimsSet.Builder().subject("analyst-user")
                .claim("role", "analyst").claim("tenant", "tenant-a").build();
        given(jwtValidator.validate("cookie-token")).willReturn(claims);
        given(jwtValidator.extractTenant(claims)).willReturn("tenant-a");
        given(chain.filter(org.mockito.ArgumentMatchers.any())).willReturn(Mono.empty());
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/v1/alarms")
                        .cookie(new HttpCookie(AuthController.SESSION_COOKIE, "cookie-token"))
                        .header("Origin", "http://localhost:5173")
                        .build());

        filter.filter(exchange, chain).block(Duration.ofSeconds(1));

        verify(chain).filter(org.mockito.ArgumentMatchers.any());
    }
}
