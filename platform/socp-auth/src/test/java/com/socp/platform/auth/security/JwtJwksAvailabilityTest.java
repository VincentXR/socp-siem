package com.socp.platform.auth.security;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.socp.platform.auth.config.SocpSecurityProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class JwtJwksAvailabilityTest {
    private HttpServer server;
    private ExecutorService executor;
    private RSAKey key;
    private volatile String jwks;
    private volatile int delayMs;
    private volatile int failedRequests;
    private final AtomicInteger requests = new AtomicInteger();

    @BeforeEach
    void start() throws Exception {
        key = new RSAKeyGenerator(2048).keyID("key-1").generate();
        jwks = new JWKSet(key.toPublicJWK()).toString();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newCachedThreadPool();
        server.setExecutor(executor);
        server.createContext("/jwks", exchange -> {
            int attempt = requests.incrementAndGet();
            try {
                Thread.sleep(delayMs);
                byte[] body = jwks.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(attempt <= failedRequests ? 503 : 200, body.length);
                exchange.getResponseBody().write(body);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } catch (java.io.IOException disconnected) {
                // A client timeout deliberately closes the socket in the timeout test.
            } finally { exchange.close(); }
        });
        server.start();
    }

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
        if (executor != null) executor.shutdownNow();
    }

    @Test
    void validColdTokenSurvivesPublicKeyLatencyAboveNimbusDefault() throws Exception {
        delayMs = 700;
        var validator = new JwtValidator(properties());
        assertEquals("user", validator.validate(token(key, 60_000)).getSubject());
        assertEquals(1, requests.get());
    }

    @Test
    void transientKeyEndpointFailureRetriesOnceAndCachesTheVerifiedKeys() throws Exception {
        failedRequests = 1;
        var validator = new JwtValidator(properties());
        String token = token(key, 60_000);
        assertDoesNotThrow(() -> validator.validate(token));
        assertEquals(2, requests.get());
        failedRequests = Integer.MAX_VALUE;
        assertDoesNotThrow(() -> validator.validate(token));
        assertEquals(2, requests.get());
        assertThrows(JwtValidationException.class, () -> validator.validate(token(key, -120_000)));
    }

    @Test
    void persistentFailureAndReadTimeoutRemainBoundedAndNeverAuthenticate() throws Exception {
        failedRequests = Integer.MAX_VALUE;
        var unavailable = assertThrows(JwtKeySourceUnavailableException.class,
                () -> new JwtValidator(properties()).validate(token(key, 60_000)));
        assertEquals("Authentication key service is temporarily unavailable", unavailable.getMessage());
        assertEquals(2, requests.get());
        requests.set(0);
        failedRequests = 0;
        delayMs = 400;
        var properties = properties();
        properties.setJwksReadTimeoutMs(100);
        assertThrows(JwtKeySourceUnavailableException.class,
                () -> new JwtValidator(properties).validate(token(key, 60_000)));
        assertEquals(2, requests.get());
    }

    @Test
    void rotatedKeyIsFetchedButAnInvalidSignatureIsStillRejected() throws Exception {
        var validator = new JwtValidator(properties());
        assertDoesNotThrow(() -> validator.validate(token(key, 60_000)));
        RSAKey rotated = new RSAKeyGenerator(2048).keyID("key-2").generate();
        jwks = new JWKSet(java.util.List.of(key.toPublicJWK(), rotated.toPublicJWK())).toString();
        assertDoesNotThrow(() -> validator.validate(token(rotated, 60_000)));
        RSAKey impostor = new RSAKeyGenerator(2048).keyID("key-2").generate();
        var failure = assertThrows(JwtValidationException.class,
                () -> validator.validate(token(impostor, 60_000)));
        assertFalse(failure instanceof JwtKeySourceUnavailableException);
    }

    @Test
    void networkBudgetsCannotDisableTimeoutsOrBecomeUnbounded() {
        var properties = properties();
        assertThrows(IllegalArgumentException.class, () -> properties.setJwksReadTimeoutMs(0));
        assertThrows(IllegalArgumentException.class, () -> properties.setJwksConnectTimeoutMs(5001));
    }

    private SocpSecurityProperties properties() {
        var properties = new SocpSecurityProperties();
        properties.setDevBypass(false);
        properties.setAudience("socp-api");
        properties.setJwkSetUri("http://127.0.0.1:" + server.getAddress().getPort() + "/jwks");
        return properties;
    }

    private static String token(RSAKey key, long expiresInMs) throws Exception {
        var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(),
                new JWTClaimsSet.Builder().subject("user").audience("socp-api")
                        .expirationTime(new Date(System.currentTimeMillis() + expiresInMs)).build());
        jwt.sign(new RSASSASigner(key));
        return jwt.serialize();
    }
}
