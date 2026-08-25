package com.socp.platform.auth.security;
import com.socp.platform.auth.config.SocpSecurityProperties;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JwtValidatorTest {

    private static final String SECRET = "test-jwt-secret-that-is-at-least-32-bytes-long";

    @Test
    void configuredAudienceAcceptsMatchingTokenAudience() throws Exception {
        SocpSecurityProperties properties = signedProperties("socp-api");
        JwtValidator validator = new JwtValidator(properties);

        assertDoesNotThrow(() -> validator.validate(token(List.of("other-client", "socp-api"))));
    }

    @Test
    void configuredAudienceRejectsWrongOrMissingTokenAudience() throws Exception {
        SocpSecurityProperties properties = signedProperties("socp-api");
        JwtValidator validator = new JwtValidator(properties);

        assertThrows(JwtValidationException.class, () -> validator.validate(token(List.of("other-client"))));
        assertThrows(JwtValidationException.class, () -> validator.validate(token(null)));
    }

    @Test
    void missingAudienceIsRejectedWhenSignatureVerificationIsEnabled() {
        SocpSecurityProperties properties = signedProperties(null);

        assertThrows(IllegalStateException.class, () -> new JwtValidator(properties));
    }

    @Test
    void pureJwksConfigurationBuildsWithoutHmacSecret() {
        SocpSecurityProperties properties = new SocpSecurityProperties();
        properties.setJwkSetUri("https://id.example.test/realms/socp/protocol/openid-connect/certs");
        properties.setAudience("socp-api");
        properties.setDevBypass(false);

        assertDoesNotThrow(() -> new JwtValidator(properties));
    }

    @Test
    void azpIsNotUsedAsTenantFallback() {
        SocpSecurityProperties properties = signedProperties("socp-api");
        JwtValidator validator = new JwtValidator(properties);
        JWTClaimsSet claims = new JWTClaimsSet.Builder().claim("azp", "other-client").build();

        assertNull(validator.extractTenant(claims));
    }

    private static SocpSecurityProperties signedProperties(String audience) {
        SocpSecurityProperties properties = new SocpSecurityProperties();
        properties.setJwtSecret(SECRET);
        properties.setDevBypass(false);
        properties.setAudience(audience);
        return properties;
    }

    private static String token(List<String> audience) throws Exception {
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                .subject("user-1")
                .expirationTime(new Date(System.currentTimeMillis() + 60_000));
        if (audience != null) {
            claims.audience(audience);
        }
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.HS256).build(), claims.build());
        jwt.sign(new MACSigner(SECRET.getBytes(StandardCharsets.UTF_8)));
        return jwt.serialize();
    }
}
