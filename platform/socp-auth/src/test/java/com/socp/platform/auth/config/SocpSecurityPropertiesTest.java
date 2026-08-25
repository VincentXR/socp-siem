package com.socp.platform.auth.config;
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

    @Test
    void audienceSupportsMultipleValues() {
        SocpSecurityProperties properties = new SocpSecurityProperties();

        assertTrue(properties.resolveAudiences().isEmpty());

        properties.setAudience(" socp-workbench, socp-api, socp-workbench ");

        assertEquals(java.util.Set.of("socp-workbench", "socp-api"), properties.resolveAudiences());
    }
}
