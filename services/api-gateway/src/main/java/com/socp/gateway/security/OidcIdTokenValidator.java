package com.socp.gateway.security;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.JWKSourceBuilder;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.proc.ConfigurableJWTProcessor;
import com.nimbusds.jwt.proc.DefaultJWTClaimsVerifier;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.LinkedHashSet;
import java.util.Set;

/** Verifies OIDC ID-token signature and protocol claims before login is trusted. */
@Component
public class OidcIdTokenValidator {

    private static final Set<JWSAlgorithm> ALGORITHMS = new LinkedHashSet<>(Set.of(
            JWSAlgorithm.RS256, JWSAlgorithm.RS384, JWSAlgorithm.RS512,
            JWSAlgorithm.ES256, JWSAlgorithm.ES384, JWSAlgorithm.ES512));

    @Value("${socp.oidc.issuer-uri:}")
    private String issuerUri;

    @Value("${socp.oidc.client-id:socp-spa}")
    private String clientId;

    private volatile ConfigurableJWTProcessor<SecurityContext> processor;

    public OidcIdTokenValidator() {
    }

    OidcIdTokenValidator(String issuerUri, String clientId) {
        this.issuerUri = issuerUri;
        this.clientId = clientId;
    }

    public JWTClaimsSet validate(String token, String expectedNonce) {
        if (token == null || token.isBlank()) throw new IllegalArgumentException("OIDC id_token is missing");
        if (expectedNonce == null || expectedNonce.isBlank()) throw new IllegalArgumentException("OIDC nonce is missing");
        try {
            JWTClaimsSet claims = processor().process(token, null);
            if (claims.getAudience() == null || !claims.getAudience().contains(clientId)) {
                throw new IllegalArgumentException("OIDC audience does not contain the configured client");
            }
            String nonce = claims.getStringClaim("nonce");
            if (!expectedNonce.equals(nonce)) throw new IllegalArgumentException("OIDC nonce mismatch");
            return claims;
        } catch (IllegalArgumentException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IllegalArgumentException("OIDC id_token validation failed", failure);
        }
    }

    private ConfigurableJWTProcessor<SecurityContext> processor() throws Exception {
        ConfigurableJWTProcessor<SecurityContext> current = processor;
        if (current != null) return current;
        synchronized (this) {
            if (processor != null) return processor;
            if (issuerUri == null || issuerUri.isBlank()) {
                throw new IllegalStateException("socp.oidc.issuer-uri is required");
            }
            String issuer = issuerUri.endsWith("/")
                    ? issuerUri.substring(0, issuerUri.length() - 1) : issuerUri;
            JWKSource<SecurityContext> keys = JWKSourceBuilder.<SecurityContext>create(
                            URI.create(issuer + "/protocol/openid-connect/certs").toURL())
                    .cache(5 * 60 * 1000L, 15 * 1000L)
                    .build();
            DefaultJWTProcessor<SecurityContext> created = new DefaultJWTProcessor<>();
            created.setJWSKeySelector(new JWSVerificationKeySelector<>(ALGORITHMS, keys));
            JWTClaimsSet exact = new JWTClaimsSet.Builder().issuer(issuer).build();
            DefaultJWTClaimsVerifier<SecurityContext> verifier =
                    new DefaultJWTClaimsVerifier<>(exact, Set.of("exp", "sub", "nonce"));
            verifier.setMaxClockSkew(60);
            created.setJWTClaimsSetVerifier(verifier);
            processor = created;
            return created;
        }
    }
}
