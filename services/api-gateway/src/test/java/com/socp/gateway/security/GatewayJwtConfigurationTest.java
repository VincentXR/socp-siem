package com.socp.gateway.security;

import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.socp.platform.auth.config.SocpSecurityProperties;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;

class GatewayJwtConfigurationTest {

    @Test
    void validatesPlatformSessionFromInProcessPublicKeyWithoutFetchingItsOwnEndpoint() throws Exception {
        RSAKey privateKey = new RSAKeyGenerator(2048).keyID("gateway-local-validation").generate();
        PlatformTokenIssuer issuer = new PlatformTokenIssuer(
                privateKey.toJSONString(), "", "https://socp.example.test", "socp-api");
        SocpSecurityProperties properties = new SocpSecurityProperties();
        properties.setIssuerUri("https://socp.example.test");
        properties.setJwkSetUri("http://127.0.0.1:9/must-not-be-called");
        properties.setAudience("socp-api");
        var validator = new GatewayJwtConfiguration().jwtValidator(properties, issuer);
        String token = issuer.sign(new JWTClaimsSet.Builder()
                .issuer(issuer.issuer())
                .audience(issuer.audiences())
                .subject("analyst")
                .claim("tenant", "tenant-a")
                .expirationTime(Date.from(Instant.now().plusSeconds(60)))
                .build());

        JWTClaimsSet claims = validator.validate(token);

        assertThat(claims.getSubject()).isEqualTo("analyst");
        assertThat(validator.extractTenant(claims)).isEqualTo("tenant-a");
    }
}
