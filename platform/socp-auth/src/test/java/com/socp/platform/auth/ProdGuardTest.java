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
                .withProperty("socp.soar.simulation-enabled", "true")
                .withProperty("socp.temporal.enabled", "false");

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> new ProdGuard(env));

        assertTrue(error.getMessage().contains("prod 启动校验失败"));
        assertTrue(error.getMessage().contains("H2"));
        assertTrue(error.getMessage().contains("dev-bypass"));
        assertTrue(error.getMessage().contains("simulation-enabled"));
        assertTrue(error.getMessage().contains("socp.temporal.enabled"));
    }

    @Test
    void rejectsMissingProductionDatabaseUrl() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("spring.datasource.url", "")
                .withProperty("socp.security.jwk-set-uri", "https://id.example.test/keys")
                .withProperty("socp.security.audience", "socp-api")
                .withProperty("socp.security.ingest-token", "production-ingest-token")
                .withProperty("socp.security.service-secret", "production-service-secret-0123456789")
                .withProperty("socp.ratelimit.backend", "redis")
                .withProperty("socp.audit.sink", "kafka")
                .withProperty("socp.audit.fail-closed", "true")
                .withProperty("socp.temporal.enabled", "true");

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> new ProdGuard(env));

        assertTrue(error.getMessage().contains("spring.datasource.url"));
    }

    @Test
    void allowsProductionStatelessServiceWithoutDatasource() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("socp.security.jwk-set-uri", "https://id.example.test/keys")
                .withProperty("socp.security.audience", "socp-api")
                .withProperty("socp.security.ingest-token", "production-ingest-token")
                .withProperty("socp.security.service-secret", "production-service-secret-0123456789")
                .withProperty("socp.ratelimit.backend", "redis")
                .withProperty("socp.audit.sink", "kafka")
                .withProperty("socp.audit.fail-closed", "true")
                .withProperty("socp.temporal.enabled", "true");

        assertDoesNotThrow(() -> new ProdGuard(env));
    }

    @Test
    void acceptsExplicitProductionConfiguration() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("spring.datasource.url", "jdbc:postgresql://db.example.test/socp")
                .withProperty("socp.security.jwt-secret", "production-secret-that-is-not-the-demo-value-012345")
                .withProperty("socp.security.audience", "socp-api")
                .withProperty("socp.security.dev-bypass", "false")
                .withProperty("socp.security.ingest-token", "production-ingest-token")
                .withProperty("socp.security.service-secret", "production-service-secret-0123456789")
                .withProperty("socp.ratelimit.backend", "redis")
                .withProperty("socp.audit.sink", "kafka")
                .withProperty("socp.audit.fail-closed", "true")
                .withProperty("socp.temporal.enabled", "true");

        assertDoesNotThrow(() -> new ProdGuard(env));
    }

    @Test
    void acceptsPureJwksProductionConfigurationWithoutHmacSecret() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("spring.datasource.url", "jdbc:postgresql://db.example.test/socp")
                .withProperty("socp.security.jwk-set-uri", "https://id.example.test/realms/socp/protocol/openid-connect/certs")
                .withProperty("socp.security.audience", "socp-api")
                .withProperty("socp.security.dev-bypass", "false")
                .withProperty("socp.security.ingest-token", "production-ingest-token")
                .withProperty("socp.security.service-secret", "production-service-secret-0123456789")
                .withProperty("socp.ratelimit.backend", "redis")
                .withProperty("socp.audit.sink", "kafka")
                .withProperty("socp.audit.fail-closed", "true")
                .withProperty("socp.temporal.enabled", "true");

        assertDoesNotThrow(() -> new ProdGuard(env));
    }

    @Test
    void rejectsPureJwksProductionConfigurationWithoutAudience() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("spring.datasource.url", "jdbc:postgresql://db.example.test/socp")
                .withProperty("socp.security.issuer-uri", "https://id.example.test/realms/socp")
                .withProperty("socp.security.dev-bypass", "false")
                .withProperty("socp.security.ingest-token", "production-ingest-token")
                .withProperty("socp.security.service-secret", "production-service-secret-0123456789")
                .withProperty("socp.ratelimit.backend", "redis")
                .withProperty("socp.audit.sink", "kafka")
                .withProperty("socp.audit.fail-closed", "true")
                .withProperty("socp.temporal.enabled", "true");

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> new ProdGuard(env));

        assertTrue(error.getMessage().contains("socp.security.audience"));
    }
}
