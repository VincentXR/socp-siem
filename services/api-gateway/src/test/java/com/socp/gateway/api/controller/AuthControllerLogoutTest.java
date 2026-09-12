package com.socp.gateway.api.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.socp.gateway.security.AuthAttemptLimiter;
import com.socp.gateway.security.TokenRevocationStore;
import com.socp.platform.auth.config.SocpSecurityProperties;
import com.socp.platform.auth.security.JwtValidator;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuthControllerLogoutTest {

    private static final String SECRET = "socp-demo-jwt-secret-0123456789abcdef0123456789abcdef";

    @Test
    void logoutRevokesJtiUntilTokenExpiryBeforeClearingCookie() throws Exception {
        SocpSecurityProperties properties = new SocpSecurityProperties();
        properties.setJwtSecret(SECRET);
        properties.setAudience("socp-api");
        properties.setDevBypass(false);
        JwtValidator validator = new JwtValidator(properties);

        AtomicReference<String> revokedJti = new AtomicReference<>();
        AtomicReference<Instant> revokedUntil = new AtomicReference<>();
        TokenRevocationStore store = new TokenRevocationStore() {
            @Override
            public Mono<Void> revoke(String jti, Instant expiresAt) {
                revokedJti.set(jti);
                revokedUntil.set(expiresAt);
                return Mono.empty();
            }

            @Override
            public Mono<Boolean> isRevoked(String jti) {
                return Mono.just(jti != null && jti.equals(revokedJti.get()));
            }
        };

        AuthController controller = new AuthController(new PermitLimiter(), new ObjectMapper(), validator, store);
        ReflectionTestUtils.setField(controller, "secret", SECRET);
        ReflectionTestUtils.setField(controller, "audience", "socp-api");
        ReflectionTestUtils.setField(controller, "usersJson", "{}");
        ReflectionTestUtils.setField(controller, "rolesJson", "{}");
        ReflectionTestUtils.setField(controller, "localesJson", "{}");
        controller.init();

        String token = controller.sign("analyst", "analyst", "default");
        var request = MockServerHttpRequest.post("/auth/logout")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .build();

        var response = controller.logout(request).block();

        assertNotNull(response);
        assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
        assertNotNull(response.getHeaders().getFirst(HttpHeaders.SET_COOKIE));
        assertNotNull(revokedJti.get());
        assertNotNull(revokedUntil.get());
        assertTrue(revokedUntil.get().isAfter(Instant.now()));
        assertTrue(store.isRevoked(revokedJti.get()).block());
    }

    private static final class PermitLimiter implements AuthAttemptLimiter {
        @Override
        public Mono<Decision> acquire(String kind, String clientAddress, String identity) {
            return Mono.just(Decision.permit());
        }

        @Override
        public Mono<Void> reset(String kind, String clientAddress, String identity) {
            return Mono.empty();
        }
    }
}
