package com.socp.gateway.filter;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.socp.gateway.security.TokenRevocationStore;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RevokedTokenWebFilterTest {
    private final TokenRevocationStore store = mock(TokenRevocationStore.class);
    private final WebFilterChain chain = mock(WebFilterChain.class);
    private final RevokedTokenWebFilter filter = new RevokedTokenWebFilter(store);

    private MockServerWebExchange exchange() throws Exception {
        SignedJWT token = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256),
                new JWTClaimsSet.Builder().jwtID("test-session").build());
        byte[] key = new byte[32];
        new java.security.SecureRandom().nextBytes(key);
        token.sign(new MACSigner(key));
        return MockServerWebExchange.from(MockServerHttpRequest.get("/search-config/api/test")
                .header("Authorization", "Bearer " + token.serialize()));
    }

    @Test
    void downstreamFailureIsNotReclassifiedAsRevocationFailure() throws Exception {
        var exchange = exchange();
        var failure = new IllegalStateException("downstream connection refused");
        when(store.isRevoked("test-session")).thenReturn(Mono.just(false));
        when(chain.filter(exchange)).thenReturn(Mono.error(failure));
        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> filter.filter(exchange, chain).block(Duration.ofSeconds(1))));
        assertNull(exchange.getResponse().getStatusCode());
    }

    @Test
    void revocationBackendFailureRemainsFailClosed() throws Exception {
        var exchange = exchange();
        when(store.isRevoked("test-session")).thenReturn(Mono.error(new IllegalStateException("store offline")));
        filter.filter(exchange, chain).block(Duration.ofSeconds(1));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, exchange.getResponse().getStatusCode());
        assertTrue(exchange.getResponse().getBodyAsString().block().contains("Session revocation service unavailable"));
        verifyNoInteractions(chain);
    }

    @Test
    void revokedSessionNeverReachesDownstream() throws Exception {
        var exchange = exchange();
        when(store.isRevoked("test-session")).thenReturn(Mono.just(true));
        filter.filter(exchange, chain).block(Duration.ofSeconds(1));
        assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());
        verifyNoInteractions(chain);
    }
}
