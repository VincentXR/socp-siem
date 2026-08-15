package com.socp.platform.auth;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProdGuardTest {

    @Test
    void rejectsDevelopmentFallbacks() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("spring.datasource.url", "jdbc:h2:file:./data")
                .withProperty("socp.security.jwt-secret", "socp-demo-jwt-secret-0123456789abcdef0123456789abcdef")
                .withProperty("socp.security.dev-bypass", "true")
                .withProperty("socp.security.ingest-token", "dev-vector-token")
                .withProperty("socp.temporal.enabled", "false");

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> new ProdGuard(env));

        assertTrue(error.getMessage().contains("prod 启动校验失败"));
        assertTrue(error.getMessage().contains("H2"));
        assertTrue(error.getMessage().contains("dev-bypass"));
        assertTrue(error.getMessage().contains("socp.temporal.enabled"));
    }

    @Test
    void acceptsExplicitProductionConfiguration() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("spring.datasource.url", "jdbc:postgresql://db.example.test/socp")
                .withProperty("socp.security.jwt-secret", "production-secret-that-is-not-the-demo-value-012345")
                .withProperty("socp.security.dev-bypass", "false")
                .withProperty("socp.security.ingest-token", "production-ingest-token")
                .withProperty("socp.temporal.enabled", "true");

        assertDoesNotThrow(() -> new ProdGuard(env));
    }
}
