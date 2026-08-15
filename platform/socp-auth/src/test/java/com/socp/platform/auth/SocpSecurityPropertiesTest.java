package com.socp.platform.auth;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SocpSecurityPropertiesTest {

    @Test
    void missingCredentialsUseDevelopmentFallback() {
        SocpSecurityProperties properties = new SocpSecurityProperties();

        assertTrue(properties.resolveDevBypass());
        assertFalse(properties.hasJwks());
        assertFalse(properties.hasSecret());
    }

    @Test
    void explicitFalseNeverSilentlyFallsBack() {
        SocpSecurityProperties properties = new SocpSecurityProperties();
        properties.setDevBypass(false);

        assertFalse(properties.resolveDevBypass());
    }

    @Test
    void issuerProducesKeycloakJwksEndpoint() {
        SocpSecurityProperties properties = new SocpSecurityProperties();
        properties.setIssuerUri("https://id.example.test/realms/socp/");

        assertTrue(properties.hasJwks());
        assertEquals("https://id.example.test/realms/socp/protocol/openid-connect/certs",
                properties.resolveJwkSetUri());
    }
}
