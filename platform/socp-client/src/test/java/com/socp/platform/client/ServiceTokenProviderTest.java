package com.socp.platform.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ServiceTokenProviderTest {

    @Test
    void parsesTokenAsJsonInsteadOfSubstringMatching() {
        assertEquals("signed-token", ServiceTokenProvider.extractToken(
                "{\"expiresIn\":300,\"token\":\"signed-token\"}"));
        assertNull(ServiceTokenProvider.extractToken("{\"message\":\"no token\"}"));
        assertNull(ServiceTokenProvider.extractToken("not-json"));
    }
}
