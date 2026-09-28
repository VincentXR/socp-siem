package com.socp.gateway.security;

import com.socp.gateway.api.controller.PlatformJwksController;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PlatformTokenIssuerTest {

    @Test
    void signsWithPrivateRsaKeyAndPublishesOnlyThePublicKey() throws Exception {
        RSAKey privateKey = new RSAKeyGenerator(2048).keyID("platform-2026-09").generate();
        PlatformTokenIssuer issuer = new PlatformTokenIssuer(
                privateKey.toJSONString(), "", "https://socp.example.test", "socp-api,automation");

        String encoded = issuer.sign(new JWTClaimsSet.Builder().subject("analyst").build());
        SignedJWT jwt = SignedJWT.parse(encoded);
        JWKSet published = JWKSet.parse(issuer.publicJwkSet());
        RSAKey publicKey = (RSAKey) published.getKeyByKeyId("platform-2026-09");

        assertThat(jwt.getHeader().getAlgorithm()).isEqualTo(JWSAlgorithm.RS256);
        assertThat(jwt.getHeader().getKeyID()).isEqualTo("platform-2026-09");
        assertThat(jwt.verify(new RSASSAVerifier(publicKey))).isTrue();
        assertThat(publicKey.isPrivate()).isFalse();
        assertThat(issuer.issuer()).isEqualTo("https://socp.example.test");
        assertThat(issuer.audiences()).isEqualTo(List.of("socp-api", "automation"));
        assertThat(issuer.asymmetric()).isTrue();
        assertThat(new PlatformJwksController(issuer).jwks()).isEqualTo(issuer.publicJwkSet());
    }

    @Test
    void refusesAProductionShapedPublicOnlyKey() throws Exception {
        RSAKey publicKey = new RSAKeyGenerator(2048).keyID("public-only").generate().toPublicJWK();

        assertThatThrownBy(() -> new PlatformTokenIssuer(
                publicKey.toJSONString(), "", "https://socp.example.test", "socp-api"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("private RSA JWK");
    }

    @Test
    void retainsADevelopmentOnlyHmacFallback() throws Exception {
        String secret = "development-session-secret-0123456789abcdef";
        PlatformTokenIssuer issuer = new PlatformTokenIssuer(
                "", secret, "socp-gateway", " socp-api, ,automation ");

        SignedJWT jwt = SignedJWT.parse(issuer.sign(
                new JWTClaimsSet.Builder().subject("developer").build()));

        assertThat(jwt.getHeader().getAlgorithm()).isEqualTo(JWSAlgorithm.HS256);
        assertThat(jwt.verify(new MACVerifier(secret))).isTrue();
        assertThat(issuer.asymmetric()).isFalse();
        assertThat(issuer.publicJwkSet()).isEqualTo(Map.of("keys", List.of()));
        assertThat(issuer.audiences()).containsExactly("socp-api", "automation");
    }

    @Test
    void rejectsIncompleteSignerConfiguration() throws Exception {
        RSAKey missingKeyId = new RSAKeyGenerator(2048).generate();

        assertThatThrownBy(() -> new PlatformTokenIssuer("", "short", "socp-gateway", "socp-api"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("at least 32 bytes");
        assertThatThrownBy(() -> new PlatformTokenIssuer("", "x".repeat(32), " ", "socp-api"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("issuer");
        assertThatThrownBy(() -> new PlatformTokenIssuer("", "x".repeat(32), "socp-gateway", " , "))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("audience");
        assertThatThrownBy(() -> new PlatformTokenIssuer(
                missingKeyId.toJSONString(), "", "socp-gateway", "socp-api"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("kid");
        assertThatThrownBy(() -> new PlatformTokenIssuer(
                "not-json", "", "socp-gateway", "socp-api"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("valid private RSA JWK");
    }
}
