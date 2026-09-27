package com.socp.gateway.security;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSSigner;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Signs the short-lived platform session consumed by every SOCP service.
 *
 * <p>The external OIDC provider proves the user's identity; it is deliberately
 * not the issuer of the internal SOCP session. Production uses one RSA private
 * JWK here and publishes only its public half through {@code PlatformJwksController}.
 * The HMAC path is retained for the local/dev profile and focused tests only.</p>
 */
@Component
public final class PlatformTokenIssuer {

    private final String issuer;
    private final List<String> audiences;
    private final JWSAlgorithm algorithm;
    private final JWSSigner signer;
    private final String keyId;
    private final JWKSet verificationJwkSet;
    private final Map<String, Object> publicJwkSet;

    public PlatformTokenIssuer(
            @Value("${socp.auth.signing-jwk:}") String signingJwk,
            @Value("${socp.auth.login-secret:}") String loginSecret,
            @Value("${socp.auth.issuer:${socp.security.issuer-uri:socp-gateway}}") String issuer,
            @Value("${socp.security.audience:socp-api}") String audience) {
        this.issuer = requireText(issuer, "socp.auth.issuer");
        this.audiences = Arrays.stream(audience == null ? new String[0] : audience.split(","))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .toList();
        if (audiences.isEmpty()) {
            throw new IllegalStateException("socp.security.audience is required for platform sessions");
        }

        if (signingJwk != null && !signingJwk.isBlank()) {
            RSAKey rsa = parsePrivateRsaKey(signingJwk);
            this.algorithm = JWSAlgorithm.RS256;
            this.signer = signer(rsa);
            this.keyId = requireText(rsa.getKeyID(), "socp.auth.signing-jwk kid");
            this.verificationJwkSet = new JWKSet(rsa.toPublicJWK());
            this.publicJwkSet = verificationJwkSet.toJSONObject();
            return;
        }

        byte[] secret = loginSecret == null ? new byte[0]
                : loginSecret.getBytes(StandardCharsets.UTF_8);
        if (secret.length < 32) {
            throw new IllegalStateException(
                    "socp.auth.signing-jwk is required, or login-secret must contain at least 32 bytes in dev");
        }
        try {
            this.algorithm = JWSAlgorithm.HS256;
            this.signer = new MACSigner(secret);
            this.keyId = null;
            this.verificationJwkSet = new JWKSet();
            this.publicJwkSet = Map.of("keys", List.of());
        } catch (Exception failure) {
            throw new IllegalStateException("Unable to configure the development session signer", failure);
        }
    }

    public String sign(JWTClaimsSet claims) {
        try {
            JWSHeader.Builder header = new JWSHeader.Builder(algorithm);
            if (keyId != null) header.keyID(keyId);
            SignedJWT jwt = new SignedJWT(header.build(), claims);
            jwt.sign(signer);
            return jwt.serialize();
        } catch (Exception failure) {
            throw new IllegalStateException("Unable to issue SOCP session", failure);
        }
    }

    public String issuer() {
        return issuer;
    }

    public List<String> audiences() {
        return audiences;
    }

    public Map<String, Object> publicJwkSet() {
        return publicJwkSet;
    }

    /** Public keys for in-process gateway session validation; never contains private RSA material. */
    public JWKSet verificationJwkSet() {
        return verificationJwkSet;
    }

    public boolean asymmetric() {
        return keyId != null;
    }

    private static RSAKey parsePrivateRsaKey(String encoded) {
        try {
            JWK parsed = JWK.parse(encoded.trim());
            if (!(parsed instanceof RSAKey rsa) || !rsa.isPrivate()) {
                throw new IllegalStateException("socp.auth.signing-jwk must be a private RSA JWK");
            }
            if (rsa.size() < 2048) {
                throw new IllegalStateException("socp.auth.signing-jwk RSA modulus must be at least 2048 bits");
            }
            requireText(rsa.getKeyID(), "socp.auth.signing-jwk kid");
            return rsa;
        } catch (IllegalStateException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IllegalStateException("socp.auth.signing-jwk is not a valid private RSA JWK", failure);
        }
    }

    private static JWSSigner signer(RSAKey rsa) {
        try {
            return new RSASSASigner(rsa);
        } catch (Exception failure) {
            throw new IllegalStateException("Unable to initialize the platform RSA signer", failure);
        }
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " must not be blank");
        }
        return value.trim();
    }
}
