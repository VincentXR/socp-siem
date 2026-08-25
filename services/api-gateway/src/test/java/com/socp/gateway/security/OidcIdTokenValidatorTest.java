package com.socp.gateway.security;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OidcIdTokenValidatorTest {

    private HttpServer server;
    private RSAKey key;
    private String issuer;

    @BeforeEach
    void startJwksEndpoint() throws Exception {
        key = new RSAKeyGenerator(2048).keyID("test-key").generate();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/issuer/protocol/openid-connect/certs", exchange -> {
            byte[] body = new JWKSet(key.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        issuer = "http://127.0.0.1:" + server.getAddress().getPort() + "/issuer";
    }

    @AfterEach
    void stopJwksEndpoint() {
        if (server != null) server.stop(0);
    }

    @Test
    void validatesSignatureIssuerAudienceExpiryAndNonce() throws Exception {
        OidcIdTokenValidator validator = new OidcIdTokenValidator(issuer, "socp-spa");
        JWTClaimsSet claims = validator.validate(token("nonce-1", "socp-spa", issuer), "nonce-1");
        assertEquals("user-1", claims.getSubject());

        assertThrows(IllegalArgumentException.class,
                () -> validator.validate(token("nonce-1", "other-client", issuer), "nonce-1"));
        assertThrows(IllegalArgumentException.class,
                () -> validator.validate(token("nonce-1", "socp-spa", issuer), "wrong-nonce"));
        assertThrows(IllegalArgumentException.class,
                () -> validator.validate(token("nonce-1", "socp-spa", issuer + "/wrong"), "nonce-1"));
    }

    private String token(String nonce, String audience, String tokenIssuer) throws Exception {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(tokenIssuer)
                .subject("user-1")
                .audience(audience)
                .expirationTime(Date.from(Instant.now().plusSeconds(120)))
                .issueTime(new Date())
                .claim("nonce", nonce)
                .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256)
                .keyID(key.getKeyID()).build(), claims);
        jwt.sign(new RSASSASigner(key));
        return jwt.serialize();
    }
}
