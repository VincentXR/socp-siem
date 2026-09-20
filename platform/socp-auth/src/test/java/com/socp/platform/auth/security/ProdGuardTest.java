package com.socp.platform.auth.security;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProdGuardTest {

    @Test
    void rejectsDevelopmentFallbacks() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("spring.datasource.url", "jdbc:h2:file:./data")
                .withProperty("socp.security.jwt-secret", "socp-demo-jwt-secret-0123456789abcdef0123456789abcdef")
                .withProperty("socp.auth.login-secret", "socp-demo-jwt-secret-0123456789abcdef0123456789abcdef")
                .withProperty("socp.security.dev-bypass", "true")
                .withProperty("socp.security.ingest-token", "dev-vector-token")
                .withProperty("socp.soar.simulation-enabled", "true")
                .withProperty("socp.temporal.enabled", "false");

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> new ProdGuard(env));

        assertTrue(error.getMessage().contains("prod 启动校验失败"));
        assertTrue(error.getMessage().contains("H2"));
        assertTrue(error.getMessage().contains("dev-bypass"));
        assertTrue(error.getMessage().contains("socp.auth.login-secret"));
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
                .withProperty("socp.security.metrics-token", "production-metrics-secret-0123456789")
                .withProperty("socp.ratelimit.backend", "redis")
                .withProperty("socp.ratelimit.fail-closed", "true")
                .withProperty("socp.audit.sink", "kafka")
                .withProperty("socp.audit.fail-closed", "true")
                .withProperty("socp.temporal.enabled", "true");

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> new ProdGuard(env));

        assertTrue(error.getMessage().contains("spring.datasource.url"));
    }

    @Test
    void rejectsDemoMaturityInProduction() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("socp.maturity", "demo")
                .withProperty("socp.security.jwk-set-uri", "https://id.example.test/keys")
                .withProperty("socp.security.audience", "socp-api")
                .withProperty("socp.security.ingest-token", "production-ingest-token")
                .withProperty("socp.security.service-secret", "production-service-secret-0123456789")
                .withProperty("socp.security.metrics-token", "production-metrics-secret-0123456789")
                .withProperty("socp.ratelimit.backend", "redis")
                .withProperty("socp.ratelimit.fail-closed", "true")
                .withProperty("socp.audit.sink", "kafka")
                .withProperty("socp.audit.fail-closed", "true")
                .withProperty("socp.temporal.enabled", "true");

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> new ProdGuard(env));

        assertTrue(error.getMessage().contains("socp.maturity=demo"));
    }

    @Test
    void rejectsSeededDemoDataInProduction() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("socp.demo-data.enabled", "true")
                .withProperty("socp.security.jwk-set-uri", "https://id.example.test/realms/socp/protocol/openid-connect/certs")
                .withProperty("socp.security.audience", "socp-api")
                .withProperty("socp.security.ingest-token", "production-ingest-token")
                .withProperty("socp.security.service-secret", "production-service-secret-0123456789")
                .withProperty("socp.security.metrics-token", "production-metrics-secret-0123456789")
                .withProperty("socp.ratelimit.backend", "redis")
                .withProperty("socp.ratelimit.fail-closed", "true")
                .withProperty("socp.audit.sink", "kafka")
                .withProperty("socp.audit.fail-closed", "true")
                .withProperty("socp.temporal.enabled", "true");

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> new ProdGuard(env));

        assertTrue(error.getMessage().contains("socp.demo-data.enabled=true"));
    }

    @Test
    void allowsProductionStatelessServiceWithoutDatasource() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("socp.security.jwk-set-uri", "https://id.example.test/keys")
                .withProperty("socp.security.audience", "socp-api")
                .withProperty("socp.security.ingest-token", "production-ingest-token")
                .withProperty("socp.security.service-secret", "production-service-secret-0123456789")
                .withProperty("socp.security.metrics-token", "production-metrics-secret-0123456789")
                .withProperty("socp.ratelimit.backend", "redis")
                .withProperty("socp.ratelimit.fail-closed", "true")
                .withProperty("socp.audit.sink", "kafka")
                .withProperty("socp.audit.fail-closed", "true")
                .withProperty("socp.temporal.enabled", "true");

        assertDoesNotThrow(() -> new ProdGuard(env));
    }

    @Test
    void acceptsExplicitProductionConfiguration() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("spring.datasource.url", "jdbc:postgresql://db.example.test/socp")
                .withProperty("socp.tenant.rls.enabled", "true")
                .withProperty("socp.security.jwt-secret", "production-secret-that-is-not-the-demo-value-012345")
                .withProperty("socp.security.allow-prod-hmac", "true")
                .withProperty("socp.security.audience", "socp-api")
                .withProperty("socp.security.dev-bypass", "false")
                .withProperty("socp.security.ingest-token", "production-ingest-token")
                .withProperty("socp.security.service-secret", "production-service-secret-0123456789")
                .withProperty("socp.security.metrics-token", "production-metrics-secret-0123456789")
                .withProperty("socp.ratelimit.backend", "redis")
                .withProperty("socp.ratelimit.fail-closed", "true")
                .withProperty("socp.audit.sink", "kafka")
                .withProperty("socp.audit.fail-closed", "true")
                .withProperty("socp.temporal.enabled", "true");

        assertDoesNotThrow(() -> new ProdGuard(env));
    }

    @Test
    void rejectsInlineSoarArtifactsInProduction() {
        MockEnvironment env = validProductionEnvironment()
                .withProperty("socp.soar.maturity", "production")
                .withProperty("socp.soar.artifacts.backend", "inline");

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> new ProdGuard(env));

        assertTrue(error.getMessage().contains("socp.soar.artifacts.backend"));
    }

    @Test
    void acceptsConfiguredHttpsSoarArtifactStore() {
        MockEnvironment env = validProductionEnvironment()
                .withProperty("socp.soar.maturity", "production")
                .withProperty("socp.soar.artifacts.backend", "s3")
                .withProperty("socp.soar.artifacts.endpoint", "https://minio.example.test")
                .withProperty("socp.soar.artifacts.bucket", "soar-artifacts")
                .withProperty("socp.soar.artifacts.access-key-ref", "k8s://platform/soar-artifacts/access-key")
                .withProperty("socp.soar.artifacts.secret-key-ref", "k8s://platform/soar-artifacts/secret-key")
                .withProperty("socp.soar.artifacts.allow-insecure", "false");

        assertDoesNotThrow(() -> new ProdGuard(env));
    }

    @Test
    void rejectsEnvironmentOnlySecretProviderInProduction() {
        MockEnvironment env = validProductionEnvironment()
                .withProperty("socp.soar.maturity", "production")
                .withProperty("socp.soar.secrets.backend", "env");

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> new ProdGuard(env));

        assertTrue(error.getMessage().contains("socp.soar.secrets.backend"));
    }

    @Test
    void rejectsEnvironmentArtifactReferencesInProduction() {
        MockEnvironment env = validProductionEnvironment()
                .withProperty("socp.soar.maturity", "production")
                .withProperty("socp.soar.artifacts.access-key-ref", "env://SOAR_S3_ACCESS");

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> new ProdGuard(env));

        assertTrue(error.getMessage().contains("socp.soar.artifacts.access-key-ref"));
    }

    @Test
    void acceptsVaultSecretProviderOverHttps() {
        MockEnvironment env = validProductionEnvironment()
                .withProperty("socp.soar.maturity", "production")
                .withProperty("socp.soar.secrets.backend", "vault")
                .withProperty("socp.soar.secrets.vault-endpoint", "https://vault.example.test")
                .withProperty("socp.soar.secrets.vault-token-ref", "k8s://platform/vault/token")
                .withProperty("socp.soar.artifacts.access-key-ref", "vault://secret/soar-artifacts#access-key")
                .withProperty("socp.soar.artifacts.secret-key-ref", "vault://secret/soar-artifacts#secret-key")
                .withProperty("socp.soar.secrets.allow-insecure", "false");

        assertDoesNotThrow(() -> new ProdGuard(env));
    }

    @Test
    void rejectsVaultSecretProviderOverHttp() {
        MockEnvironment env = validProductionEnvironment()
                .withProperty("socp.soar.maturity", "production")
                .withProperty("socp.soar.secrets.backend", "vault")
                .withProperty("socp.soar.secrets.vault-endpoint", "http://vault.example.test")
                .withProperty("socp.soar.secrets.vault-token-ref", "env://SOAR_VAULT_TOKEN");

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> new ProdGuard(env));

        assertTrue(error.getMessage().contains("vault-endpoint"));
    }

    @Test
    void rejectsProductionHmacUnlessExplicitlyAllowed() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("spring.datasource.url", "jdbc:postgresql://db.example.test/socp")
                .withProperty("socp.security.jwt-secret", "production-secret-that-is-not-the-demo-value-012345")
                .withProperty("socp.security.audience", "socp-api")
                .withProperty("socp.security.ingest-token", "production-ingest-token")
                .withProperty("socp.security.service-secret", "production-service-secret-0123456789")
                .withProperty("socp.security.metrics-token", "production-metrics-secret-0123456789")
                .withProperty("socp.ratelimit.backend", "redis")
                .withProperty("socp.ratelimit.fail-closed", "true")
                .withProperty("socp.audit.sink", "kafka")
                .withProperty("socp.audit.fail-closed", "true")
                .withProperty("socp.temporal.enabled", "true");

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> new ProdGuard(env));

        assertTrue(error.getMessage().contains("allow-prod-hmac"));
    }

    @Test
    void acceptsPureJwksProductionConfigurationWithoutHmacSecret() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("spring.datasource.url", "jdbc:postgresql://db.example.test/socp")
                .withProperty("socp.tenant.rls.enabled", "true")
                .withProperty("socp.security.jwk-set-uri", "https://id.example.test/realms/socp/protocol/openid-connect/certs")
                .withProperty("socp.security.audience", "socp-api")
                .withProperty("socp.security.dev-bypass", "false")
                .withProperty("socp.security.ingest-token", "production-ingest-token")
                .withProperty("socp.security.service-secret", "production-service-secret-0123456789")
                .withProperty("socp.security.metrics-token", "production-metrics-secret-0123456789")
                .withProperty("socp.ratelimit.backend", "redis")
                .withProperty("socp.ratelimit.fail-closed", "true")
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

    @Test
    void searchConfigRequiresPerCollectorIdentity() {
        MockEnvironment env = validProductionEnvironment()
                .withProperty("spring.application.name", "search-config")
                .withProperty("socp.security.allow-global-ingest-token", "true");

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> new ProdGuard(env));

        assertTrue(error.getMessage().contains("registered collector credentials"));
        assertTrue(error.getMessage().contains("allow-global-ingest-token"));
    }

    @Test
    void rejectsKnownDefaultCollectorSecret() {
        MockEnvironment env = validProductionEnvironment()
                .withProperty("spring.application.name", "search-config")
                .withProperty("socp.security.collector-credentials",
                        "vector-default|tenant-a|dev-vector-token")
                .withProperty("socp.security.allow-global-ingest-token", "false");

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> new ProdGuard(env));

        assertTrue(error.getMessage().contains("collector-credentials"));
    }

    private static final String COLLECTOR_SECRET = "a-long-production-collector-secret";
    private static final String COLLECTOR_SECRET_NEXT = "the-next-long-collector-secret-a";

    private static String futureNotAfter(long days) {
        return java.time.Instant.now().plus(days, java.time.temporal.ChronoUnit.DAYS)
                .truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString();
    }

    @Test
    void acceptsSearchConfigWhenVectorCredentialIsRegistered() {
        MockEnvironment env = validProductionEnvironment()
                .withProperty("spring.application.name", "search-config")
                .withProperty("socp.security.collector-credentials",
                        "vector-prod|tenant-a|" + COLLECTOR_SECRET + "|" + futureNotAfter(90))
                .withProperty("socp.security.allow-global-ingest-token", "false")
                .withProperty("socp.vector.token", COLLECTOR_SECRET);

        assertDoesNotThrow(() -> new ProdGuard(env));
    }

    @Test
    void acceptsRotationGracePeriodWithTwoLiveSecrets() {
        // Rolling a collector means both versions validate for a bounded window; the
        // Vector token may still be the old one while the fleet updates.
        MockEnvironment env = validProductionEnvironment()
                .withProperty("spring.application.name", "search-config")
                .withProperty("socp.security.collector-credentials",
                        "vector-prod|tenant-a|" + COLLECTOR_SECRET + "|" + futureNotAfter(7)
                                + ";vector-prod|tenant-a|" + COLLECTOR_SECRET_NEXT + "|"
                                + futureNotAfter(97))
                .withProperty("socp.security.allow-global-ingest-token", "false")
                .withProperty("socp.vector.token", COLLECTOR_SECRET);

        assertDoesNotThrow(() -> new ProdGuard(env));
    }

    @Test
    void rejectsCollectorCredentialWithoutExpiry() {
        MockEnvironment env = validProductionEnvironment()
                .withProperty("spring.application.name", "search-config")
                .withProperty("socp.security.collector-credentials",
                        "vector-prod|tenant-a|" + COLLECTOR_SECRET)
                .withProperty("socp.security.allow-global-ingest-token", "false")
                .withProperty("socp.vector.token", COLLECTOR_SECRET);

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> new ProdGuard(env));

        assertTrue(error.getMessage().contains("no notAfter"));
    }

    @Test
    void rejectsCollectorCredentialBelowTheStrengthFloor() {
        MockEnvironment env = validProductionEnvironment()
                .withProperty("spring.application.name", "search-config")
                .withProperty("socp.security.collector-credentials",
                        "vector-prod|tenant-a|short-secret|" + futureNotAfter(30))
                .withProperty("socp.security.allow-global-ingest-token", "false")
                .withProperty("socp.vector.token", "short-secret");

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> new ProdGuard(env));

        assertTrue(error.getMessage().contains("shorter than 32 bytes"));
    }

    @Test
    void rejectsAlreadyExpiredOrOverlongCollectorCredentials() {
        MockEnvironment expired = validProductionEnvironment()
                .withProperty("spring.application.name", "search-config")
                .withProperty("socp.security.collector-credentials",
                        "vector-prod|tenant-a|" + COLLECTOR_SECRET + "|2000-01-01T00:00:00Z")
                .withProperty("socp.security.allow-global-ingest-token", "false")
                .withProperty("socp.vector.token", COLLECTOR_SECRET);

        assertTrue(assertThrows(IllegalStateException.class, () -> new ProdGuard(expired))
                .getMessage().contains("expired at"));

        MockEnvironment overlong = validProductionEnvironment()
                .withProperty("spring.application.name", "search-config")
                .withProperty("socp.security.collector-credentials",
                        "vector-prod|tenant-a|" + COLLECTOR_SECRET + "|" + futureNotAfter(400))
                .withProperty("socp.security.allow-global-ingest-token", "false")
                .withProperty("socp.vector.token", COLLECTOR_SECRET);

        assertTrue(assertThrows(IllegalStateException.class, () -> new ProdGuard(overlong))
                .getMessage().contains("366"));
    }

    @Test
    void rejectsUnboundedCollectorRotationGracePeriod() {
        // Two live secrets are allowed only when the replaced one has a deadline.
        MockEnvironment env = validProductionEnvironment()
                .withProperty("spring.application.name", "search-config")
                .withProperty("socp.security.collector-credentials",
                        "vector-prod|tenant-a|" + COLLECTOR_SECRET + "|" + futureNotAfter(7)
                                + ";vector-prod|tenant-a|" + COLLECTOR_SECRET_NEXT)
                .withProperty("socp.security.allow-global-ingest-token", "false")
                .withProperty("socp.vector.token", COLLECTOR_SECRET);

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> new ProdGuard(env));

        // The registry owns the shape rule and reports it on first use; ProdGuard must
        // not additionally claim the secret is undated for the vector token match.
        assertTrue(error.getMessage().contains("collector"));
    }

    @Test
    void rejectsTrustAllOpenSearchAndKnownDefaults() {
        MockEnvironment env = validProductionEnvironment()
                .withProperty("socp.opensearch.enabled", "true")
                .withProperty("socp.opensearch.url", "http://search.example.test:9200")
                .withProperty("socp.opensearch.username", "admin")
                .withProperty("socp.opensearch.password", "Socp!Sec2026xK")
                .withProperty("socp.opensearch.tls.insecure-skip-verify", "true");

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> new ProdGuard(env));

        assertTrue(error.getMessage().contains("trust-all TLS"));
        assertTrue(error.getMessage().contains("must use HTTPS"));
        assertTrue(error.getMessage().contains("known development default"));
    }

    @Test
    void rejectsProcessLocalGatewaySessionBackendsInProduction() {
        MockEnvironment env = validProductionEnvironment()
                .withProperty("spring.application.name", "api-gateway")
                .withProperty("socp.auth.cookie-secure", "true")
                .withProperty("socp.auth.revocation.backend", "memory")
                .withProperty("socp.oidc.state.backend", "memory");

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> new ProdGuard(env));

        assertTrue(error.getMessage().contains("socp.auth.revocation.backend=memory"));
        assertTrue(error.getMessage().contains("socp.oidc.state.backend=memory"));
    }

    /**
     * 网关会话 cookie 的默认值是「不Secure」，所以漏配必须自己成为违规项：
     * 未显式置 true 时启动即失败，而不是悄悄把会话令牌以明文 cookie 发到 HTTP 上。
     */
    @Test
    void rejectsInsecureGatewaySessionCookiesByDefault() {
        MockEnvironment env = validProductionEnvironment()
                .withProperty("spring.application.name", "api-gateway")
                .withProperty("socp.auth.revocation.backend", "redis")
                .withProperty("socp.oidc.state.backend", "redis");

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> new ProdGuard(env));

        assertTrue(error.getMessage().contains("socp.auth.cookie-secure=false"),
                "违规项必须点名 cookie 开关，实际=" + error.getMessage());
        assertTrue(error.getMessage().contains("HTTPS"));
        assertFalse(error.getMessage().contains("revocation.backend"),
                "共享后端已配 redis，不得被这条违规一并误报");
        assertFalse(error.getMessage().contains("oidc.state.backend"));

        env.withProperty("socp.auth.cookie-secure", "true");
        assertDoesNotThrow(() -> new ProdGuard(env), "显式 Secure 后网关侧唯一违规就是该开关，修掉即通过");
    }

    /** 显式 false 与漏配同义：生产不得用「没读到属性」当作允许明文 cookie 的理由。 */
    @Test
    void rejectsExplicitlyDisabledGatewaySessionCookieSecurity() {
        MockEnvironment env = validProductionEnvironment()
                .withProperty("spring.application.name", "api-gateway")
                .withProperty("socp.auth.cookie-secure", "false")
                .withProperty("socp.auth.revocation.backend", "redis")
                .withProperty("socp.oidc.state.backend", "redis");

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> new ProdGuard(env));

        assertTrue(error.getMessage().contains("socp.auth.cookie-secure=false"));
    }

    @Test
    void rejectsBlankRateLimitBackendAsUnsupportedNotRedis() {
        MockEnvironment env = validProductionEnvironment()
                .withProperty("socp.ratelimit.backend", "");

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> new ProdGuard(env));

        assertTrue(error.getMessage().contains("socp.ratelimit.backend=<blank>"));
    }

    @Test
    void rejectsMisspelledRateLimitBackendEvenWithFailClosed() {
        MockEnvironment env = validProductionEnvironment()
                .withProperty("socp.ratelimit.backend", "reids")
                .withProperty("socp.ratelimit.fail-closed", "true");

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> new ProdGuard(env));

        assertTrue(error.getMessage().contains("socp.ratelimit.backend=reids"));
    }

    @Test
    void requiresDedicatedFlywayMigrationRoleOnPostgres() {
        MockEnvironment env = validProductionEnvironment()
                .withProperty("spring.datasource.username", "socp")
                .withProperty("spring.flyway.enabled", "true")
                .withProperty("spring.flyway.url", "jdbc:postgresql://db.example.test/socp")
                .withProperty("spring.flyway.user", "socp");

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> new ProdGuard(env));

        assertTrue(error.getMessage().contains("spring.flyway.user"));
    }

    /**
     * 与运行时角色「不同」还不够：迁移角色落在 postgres / socp 这类已知本地默认上，
     * 等价于把 DDL 权限留在人人都知道的账号里，必须单独成为违规项。
     */
    @Test
    void rejectsAKnownLocalDefaultMigrationRoleEvenWhenItDiffersFromRuntime() {
        MockEnvironment env = validProductionEnvironment()
                .withProperty("spring.datasource.username", "socp_app_runtime")
                .withProperty("spring.flyway.enabled", "true")
                .withProperty("spring.flyway.url", "jdbc:postgresql://db.example.test/socp")
                .withProperty("spring.flyway.user", "postgres")
                .withProperty("spring.flyway.password", "a-dedicated-migration-password");

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> new ProdGuard(env));

        assertTrue(error.getMessage().contains("known local default"),
                "必须点出「已知本地默认角色」这一具体原因，实际=" + error.getMessage());
        assertFalse(error.getMessage().contains("must differ from spring.datasource.username"),
                "角色本就没和运行时重合，不得误报成撞名");
        assertFalse(error.getMessage().contains("must be an explicit dedicated migration role"),
                "角色已显式配置，不得误报成缺失");

        // 大小写不敏感：POSTGRES/socp 这类默认名同样命中，换成真正专用的角色后整套配置通过。
        env.withProperty("spring.flyway.user", "POSTGRES");
        IllegalStateException uppercaseDefault =
                assertThrows(IllegalStateException.class, () -> new ProdGuard(env));
        assertTrue(uppercaseDefault.getMessage().contains("known local default"));
        env.withProperty("spring.flyway.user", "socp");
        IllegalStateException runtimeUserNamedDefault =
                assertThrows(IllegalStateException.class, () -> new ProdGuard(env));
        assertTrue(runtimeUserNamedDefault.getMessage().contains("known local default"));
        env.withProperty("spring.flyway.user", "socp_migrator");
        assertDoesNotThrow(() -> new ProdGuard(env));
    }

    /** 撞名检查优先于默认名检查：同一账号既撞运行时又落在默认上时只报一次。 */
    @Test
    void reportsRuntimeCollisionBeforeTheDefaultNameForTheSameMigrationRole() {
        MockEnvironment env = validProductionEnvironment()
                .withProperty("spring.datasource.username", "socp")
                .withProperty("spring.flyway.enabled", "true")
                .withProperty("spring.flyway.url", "jdbc:postgresql://db.example.test/socp")
                .withProperty("spring.flyway.user", "socp")
                .withProperty("spring.flyway.password", "a-dedicated-migration-password");

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> new ProdGuard(env));

        assertTrue(error.getMessage().contains("must differ from spring.datasource.username"));
        assertFalse(error.getMessage().contains("known local default"));
    }

    @Test
    void rejectsMissingFlywayMigrationCredentialsOnPostgres() {
        MockEnvironment env = validProductionEnvironment()
                .withProperty("spring.datasource.username", "socp")
                .withProperty("spring.flyway.enabled", "true")
                .withProperty("spring.flyway.url", "jdbc:postgresql://db.example.test/socp");

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> new ProdGuard(env));

        assertTrue(error.getMessage().contains("spring.flyway.user"));
        assertTrue(error.getMessage().contains("spring.flyway.password"));
    }

    @Test
    void acceptsSeparateFlywayMigrationRoleOnPostgres() {
        MockEnvironment env = validProductionEnvironment()
                .withProperty("spring.datasource.username", "socp")
                .withProperty("spring.flyway.enabled", "true")
                .withProperty("spring.flyway.url", "jdbc:postgresql://db.example.test/socp")
                .withProperty("spring.flyway.user", "socp_migrator")
                .withProperty("spring.flyway.password", "a-dedicated-migration-password");

        assertDoesNotThrow(() -> new ProdGuard(env));
    }

    @Test
    void flywayMigrationRoleGuardIsSkippedWhenFlywayDisabled() {
        MockEnvironment env = validProductionEnvironment()
                .withProperty("spring.datasource.username", "socp")
                .withProperty("spring.flyway.enabled", "false");

        assertDoesNotThrow(() -> new ProdGuard(env));
    }

    /**
     * 独立迁移角色是 PostgreSQL 契约（SOCP_PG_MIGRATION_*）：Flyway 目标不是 pg 时
     * 必须整体跳过，否则一个本就没有 pg 迁移角色的服务会被判成双重违规而起不来。
     */
    @Test
    void flywayMigrationRoleGuardIsSkippedForNonPostgresTargets() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("socp.security.jwk-set-uri", "https://id.example.test/keys")
                .withProperty("socp.security.audience", "socp-api")
                .withProperty("socp.security.ingest-token", "production-ingest-token")
                .withProperty("socp.security.service-secret", "production-service-secret-0123456789")
                .withProperty("socp.security.metrics-token", "production-metrics-secret-0123456789")
                .withProperty("socp.ratelimit.backend", "redis")
                .withProperty("socp.ratelimit.fail-closed", "true")
                .withProperty("socp.audit.sink", "kafka")
                .withProperty("socp.audit.fail-closed", "true")
                .withProperty("socp.temporal.enabled", "true")
                .withProperty("spring.flyway.enabled", "true")
                .withProperty("spring.flyway.url", "jdbc:oracle:thin:@//db.example.test:1521/SOCP");

        assertDoesNotThrow(() -> new ProdGuard(env),
                "非 postgres 的 Flyway 目标不得被要求提供 pg 专用迁移角色");

        // 同一环境仅把 Flyway 指向 pg，缺失的迁移角色/口令立刻成为违规：
        // 证明上一条通过来自数据库类型判定，而不是「属性没读到」。
        env.withProperty("spring.flyway.url", "jdbc:postgresql://db.example.test/socp");
        IllegalStateException error = assertThrows(IllegalStateException.class, () -> new ProdGuard(env));
        assertTrue(error.getMessage().contains("spring.flyway.user"));
        assertTrue(error.getMessage().contains("spring.flyway.password"));
    }

    /** 数据库类型看的是两侧连接串：运行时 datasource 是 pg 时，Flyway 也必须交代迁移角色。 */
    @Test
    void migrationRoleContractFollowsTheRuntimeDatasourceType() {
        MockEnvironment env = validProductionEnvironment()
                .withProperty("spring.flyway.enabled", "true")
                .withProperty("spring.flyway.url", "jdbc:oracle:thin:@//db.example.test:1521/SOCP");

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> new ProdGuard(env));

        assertTrue(error.getMessage().contains("spring.flyway.user"));
    }

    @Test
    void acceptsSharedGatewaySessionBackendsInProduction() {
        MockEnvironment env = validProductionEnvironment()
                .withProperty("spring.application.name", "api-gateway")
                .withProperty("socp.auth.cookie-secure", "true")
                .withProperty("socp.auth.revocation.backend", "redis")
                .withProperty("socp.oidc.state.backend", "redis");

        assertDoesNotThrow(() -> new ProdGuard(env));
    }

    @Test
    void gatewayAssertionsDoNotApplyToServletServices() {
        // Servlet services do not read those keys at all; only the gateway must
        // resolve them to the shared backend.
        MockEnvironment env = validProductionEnvironment()
                .withProperty("spring.application.name", "alert-web");

        assertDoesNotThrow(() -> new ProdGuard(env));
    }

    private static MockEnvironment validProductionEnvironment() {
        return new MockEnvironment()
                .withProperty("spring.datasource.url", "jdbc:postgresql://db.example.test/socp")
                .withProperty("socp.tenant.rls.enabled", "true")
                .withProperty("socp.security.jwk-set-uri", "https://id.example.test/keys")
                .withProperty("socp.security.audience", "socp-api")
                .withProperty("socp.security.ingest-token", "production-ingest-token")
                .withProperty("socp.security.service-secret", "production-service-secret-0123456789")
                .withProperty("socp.security.metrics-token", "production-metrics-secret-0123456789")
                .withProperty("socp.ratelimit.backend", "redis")
                .withProperty("socp.ratelimit.fail-closed", "true")
                .withProperty("socp.audit.sink", "kafka")
                .withProperty("socp.audit.fail-closed", "true")
                .withProperty("socp.temporal.enabled", "true")
                .withProperty("socp.soar.artifacts.backend", "s3")
                .withProperty("socp.soar.artifacts.endpoint", "https://objects.example.test")
                .withProperty("socp.soar.artifacts.bucket", "soar-artifacts")
                .withProperty("socp.soar.artifacts.access-key-ref", "k8s://platform/object-store/access")
                .withProperty("socp.soar.artifacts.secret-key-ref", "k8s://platform/object-store/secret")
                .withProperty("socp.soar.secrets.backend", "kubernetes")
                .withProperty("socp.soar.secrets.kubernetes-mount-path", "/var/run/secrets/socp");
    }
}
