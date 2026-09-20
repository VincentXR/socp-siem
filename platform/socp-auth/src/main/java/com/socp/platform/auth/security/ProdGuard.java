package com.socp.platform.auth.security;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 生产模式启动守卫（2026-08-12，P1）。
 *
 * <p>三档 profile 的「prod」侧：激活 {@code spring.profiles.active=prod} 时执行严格启动校验，
 * 任一违规直接 {@link IllegalStateException} 中止启动（fail-fast），绝不带着隐患跑生产：
 * <ul>
 *   <li>禁止 H2 文件库（spring.datasource.url 含 jdbc:h2）</li>
 *   <li>禁止默认演示 JWT secret（run-all.sh 注入的 demo 值）</li>
 *   <li>禁止 dev-bypass=true（鉴权不得绕过）</li>
 *   <li>禁止默认采集凭据（dev-vector-token），并要求每条采集凭据达到最小强度、
 *       带未过期的 {@code notAfter} 到期时间（生产采集凭据必须轮换，不允许无限期）</li>
 *   <li>要求 metrics 凭据非默认（ActuatorAuthFilter 在 actuator 路径上消费它）</li>
 *   <li>要求限流后端为共享 redis（正向白名单，非 redis/空/拼错一律拒绝）</li>
 *   <li>要求 prod+pg 下 Flyway 使用独立迁移角色，且 ≠ 运行时角色、非本地默认</li>
 *   <li>要求网关的会话撤销 / OIDC state 后端为共享 redis</li>
 *   <li>禁止关闭 Temporal（SOAR 不得回退进程内执行器）</li>
 * </ul>
 *
 * <p>local（默认）：H2/内存回退/默认凭据全允许，开发便利；
 * integration：run-all + compose 全中间件；prod：本守卫 + 环境变量注入真实组件。
 */
@Configuration
@Profile("prod")
public class ProdGuard {

    private static final Logger log = LoggerFactory.getLogger(ProdGuard.class);

    private static final String DEMO_JWT_SECRET = "socp-demo-jwt-secret-0123456789abcdef0123456789abcdef";
    private static final String DEMO_INGEST_TOKEN = "dev-vector-token";
    private static final String DEMO_SERVICE_SECRET = "socp-demo-service-secret-change-me";
    private static final String DEMO_METRICS_TOKEN = "socp-demo-metrics-token";

    public ProdGuard(Environment env) {
        List<String> violations = new ArrayList<>();

        String url = env.getProperty("spring.datasource.url", "");
        if (env.containsProperty("spring.datasource.url") && url.isBlank()) {
            violations.add("spring.datasource.url is not configured in production");
        } else if (env.containsProperty("spring.datasource.url")
                && !url.toLowerCase(java.util.Locale.ROOT).startsWith("jdbc:postgresql:")) {
            violations.add("spring.datasource.url 指向 H2（生产禁止 H2，请配置 SOCP_PG_* 指向 PostgreSQL）");
        }
        if (env.containsProperty("spring.datasource.url")
                && !isEnabled(env, "socp.tenant.rls.enabled")) {
            violations.add("socp.tenant.rls.enabled must be true for production database services");
        }

        String secret = env.getProperty("socp.security.jwt-secret", "");
        String issuerUri = env.getProperty("socp.security.issuer-uri", "");
        String jwkSetUri = env.getProperty("socp.security.jwk-set-uri", "");
        boolean hasSecret = !secret.isBlank();
        boolean hasJwks = !issuerUri.isBlank() || !jwkSetUri.isBlank();
        if (!hasSecret && !hasJwks) {
            violations.add("socp.security must configure exactly one JWT verification source: issuer-uri/jwk-set-uri or jwt-secret");
        } else if (hasSecret && hasJwks) {
            violations.add("socp.security must not configure both issuer-uri/jwk-set-uri and jwt-secret");
        }
        String audience = env.getProperty("socp.security.audience", "");
        boolean hasAudience = Arrays.stream(audience.split(","))
                .map(String::trim)
                .anyMatch(value -> !value.isBlank());
        if (hasSecret && secret.getBytes(java.nio.charset.StandardCharsets.UTF_8).length < 32) {
            violations.add("socp.security.jwt-secret must be at least 32 bytes for HS256");
        }
        if ((hasSecret || hasJwks) && !hasAudience) {
            violations.add("socp.security.audience must be configured for every production JWT verifier");
        }
        if (hasSecret && !hasJwks
                && !Boolean.parseBoolean(env.getProperty("socp.security.allow-prod-hmac", "false"))) {
            violations.add("production JWT verification must use JWKS/issuer; HMAC requires explicit socp.security.allow-prod-hmac=true");
        }
        if (DEMO_JWT_SECRET.equals(secret)) {
            violations.add("socp.security.jwt-secret 使用了默认演示密钥（run-all.sh 的 demo 值）");
        }

        if ("true".equalsIgnoreCase(env.getProperty("socp.security.dev-bypass", "false"))) {
            violations.add("socp.security.dev-bypass=true（生产禁止绕过 JWT 验签）");
        }

        String ingest = env.getProperty("socp.security.ingest-token", "");
        if (DEMO_INGEST_TOKEN.equals(ingest)) {
            violations.add("socp.security.ingest-token 使用了默认演示值 dev-vector-token");
        }

        String application = env.getProperty("spring.application.name", "");
        if ("search-config".equals(application) || "hips-web".equals(application)) {
            String collectors = env.getProperty("socp.security.collector-credentials", "");
            if (collectors.isBlank()) {
                violations.add(application + " production requires registered collector credentials");
            } else {
                validateCollectorCredentials(collectors, violations);
                if ("search-config".equals(application)
                        && !collectorSecretsContain(collectors, env.getProperty("socp.vector.token", ""))) {
                    violations.add("socp.vector.token must match a registered collector secret");
                }
            }
            if (Boolean.parseBoolean(env.getProperty("socp.security.allow-global-ingest-token", "true"))) {
                violations.add("socp.security.allow-global-ingest-token=true (production requires per-collector identity)");
            }
        }

        String serviceSecret = env.getProperty("socp.security.service-secret", "");
        if (serviceSecret.isBlank()) {
            violations.add("socp.security.service-secret is not configured");
        } else if (DEMO_SERVICE_SECRET.equals(serviceSecret)) {
            violations.add("socp.security.service-secret uses the development default");
        }

        String metricsToken = env.getProperty("socp.security.metrics-token", "");
        if (metricsToken.isBlank()) {
            violations.add("socp.security.metrics-token is not configured");
        } else if (DEMO_METRICS_TOKEN.equals(metricsToken)) {
            violations.add("socp.security.metrics-token uses the development default");
        }

        if (!"true".equalsIgnoreCase(env.getProperty("socp.temporal.enabled", "true"))) {
            violations.add("socp.temporal.enabled=false（生产禁止 SOAR 回退进程内执行器）");
        }

        // SOAR run projections may retain only metadata for large evidence in
        // production.  Inline PostgreSQL artifacts are a preview convenience,
        // not a safe high-throughput retention backend.
        if ("production".equalsIgnoreCase(env.getProperty("socp.soar.maturity", "").trim())) {
            String artifactBackend = env.getProperty("socp.soar.artifacts.backend", "inline");
            if (!"s3".equalsIgnoreCase(artifactBackend)) {
                violations.add("socp.soar.artifacts.backend must be s3 in production");
            } else {
                String endpoint = env.getProperty("socp.soar.artifacts.endpoint", "");
                if (endpoint.isBlank() || !endpoint.toLowerCase(java.util.Locale.ROOT).startsWith("https://")) {
                    violations.add("socp.soar.artifacts.endpoint must be an HTTPS S3 endpoint in production");
                }
                if (Boolean.parseBoolean(env.getProperty("socp.soar.artifacts.allow-insecure", "false"))) {
                    violations.add("socp.soar.artifacts.allow-insecure=true (production forbids insecure object storage)");
                }
                for (String key : List.of("socp.soar.artifacts.bucket",
                        "socp.soar.artifacts.access-key-ref", "socp.soar.artifacts.secret-key-ref")) {
                    if (env.getProperty(key, "").isBlank()) violations.add(key + " is required in production");
                }
            }

            String secretBackend = env.getProperty("socp.soar.secrets.backend", "kubernetes").trim();
            String normalizedSecretBackend = secretBackend.toLowerCase(java.util.Locale.ROOT);
            if (!List.of("kubernetes", "vault").contains(normalizedSecretBackend)) {
                violations.add("socp.soar.secrets.backend must be kubernetes or vault in production");
            } else if (Boolean.parseBoolean(env.getProperty("socp.soar.secrets.allow-environment-fallback", "false"))) {
                violations.add("socp.soar.secrets.allow-environment-fallback=true (production requires rotatable provider references)");
            } else if ("kubernetes".equalsIgnoreCase(secretBackend)) {
                String mountPath = env.getProperty("socp.soar.secrets.kubernetes-mount-path",
                        "/var/run/secrets/socp");
                if (mountPath.isBlank() || !mountPath.startsWith("/")) {
                    violations.add("socp.soar.secrets.kubernetes-mount-path must be an absolute path in production");
                }
            } else {
                String vaultEndpoint = env.getProperty("socp.soar.secrets.vault-endpoint", "");
                if (vaultEndpoint.isBlank()
                        || !vaultEndpoint.toLowerCase(java.util.Locale.ROOT).startsWith("https://")) {
                    violations.add("socp.soar.secrets.vault-endpoint must be an HTTPS endpoint in production");
                }
                if (Boolean.parseBoolean(env.getProperty("socp.soar.secrets.allow-insecure", "false"))) {
                    violations.add("socp.soar.secrets.allow-insecure=true (production forbids insecure secret transport)");
                }
                if (env.getProperty("socp.soar.secrets.vault-token-ref", "").isBlank()) {
                    violations.add("socp.soar.secrets.vault-token-ref is required in production");
                } else if (!env.getProperty("socp.soar.secrets.vault-token-ref", "").trim()
                        .toLowerCase(java.util.Locale.ROOT).startsWith("k8s://")) {
                    violations.add("socp.soar.secrets.vault-token-ref must use k8s:// in production");
                }
            }
            String accessRef = env.getProperty("socp.soar.artifacts.access-key-ref", "").trim();
            String secretRef = env.getProperty("socp.soar.artifacts.secret-key-ref", "").trim();
            String expectedArtifactScheme = "vault".equalsIgnoreCase(normalizedSecretBackend)
                    ? "vault://" : "k8s://";
            if (!accessRef.isBlank() && !accessRef.toLowerCase(java.util.Locale.ROOT).startsWith(expectedArtifactScheme)) {
                violations.add("socp.soar.artifacts.access-key-ref must use " + expectedArtifactScheme + " in production");
            }
            if (!secretRef.isBlank() && !secretRef.toLowerCase(java.util.Locale.ROOT).startsWith(expectedArtifactScheme)) {
                violations.add("socp.soar.artifacts.secret-key-ref must use " + expectedArtifactScheme + " in production");
            }
        }

        for (String simulationProperty : List.of("socp.soar.simulation-enabled")) {
            if ("true".equalsIgnoreCase(env.getProperty(simulationProperty, "false"))) {
                violations.add(simulationProperty + "=true (production forbids simulated actions and collectors)");
            }
        }

        for (String maturityProperty : List.of("socp.maturity", "socp.ai.maturity", "socp.soar.maturity")) {
            if ("demo".equalsIgnoreCase(env.getProperty(maturityProperty, "").trim())) {
                violations.add(maturityProperty + "=demo (production forbids demo maturity services)");
            }
        }

        if ("true".equalsIgnoreCase(env.getProperty("socp.demo-data.enabled", "false"))) {
            violations.add("socp.demo-data.enabled=true (production forbids seeded demo data)");
        }

        String rateLimitBackend = env.getProperty("socp.ratelimit.backend", "memory").trim();
        if (!"redis".equalsIgnoreCase(rateLimitBackend)) {
            violations.add("socp.ratelimit.backend="
                    + (rateLimitBackend.isBlank() ? "<blank>" : rateLimitBackend)
                    + " (production requires the shared redis backend)");
        } else if (!"true".equalsIgnoreCase(env.getProperty("socp.ratelimit.fail-closed", "false"))) {
            violations.add("socp.ratelimit.fail-closed must be true in production");
        }

        if (!"kafka".equalsIgnoreCase(env.getProperty("socp.audit.sink", "memory"))) {
            violations.add("socp.audit.sink must be kafka in production");
        }

        if (!"true".equalsIgnoreCase(env.getProperty("socp.audit.fail-closed", "false"))) {
            violations.add("socp.audit.fail-closed must be true in production");
        }

        if ("api-gateway".equals(application)) {
            if (!"true".equalsIgnoreCase(env.getProperty("socp.auth.cookie-secure", "false"))) {
                violations.add("socp.auth.cookie-secure=false (production session cookies require HTTPS)");
            }
            // Session revocation and OIDC PKCE state are correctness state shared by
            // every gateway replica. A process-local backend keeps a logged-out
            // session alive for the replicas that never saw the logout, and lets an
            // authorization callback land on a replica without its PKCE state.
            requireSharedBackend(env, violations, "socp.auth.revocation.backend");
            requireSharedBackend(env, violations, "socp.oidc.state.backend");
        }

        validateConfiguredCredential(env, violations, "spring.datasource.password", List.of("", "socp"));
        validateConfiguredCredential(env, violations, "socp.ck.password", List.of("", "socp"));
        validateConfiguredCredential(env, violations, "socp.minio.secret-key", List.of("", "Socp@2026"));
        validateConfiguredCredential(env, violations, "socp.auth.login-secret",
                List.of("", DEMO_JWT_SECRET));
        validateConfiguredCredential(env, violations, "socp.vector.token", List.of("", DEMO_INGEST_TOKEN));
        validateConfiguredCredential(env, violations, "socp.opensearch.password", List.of("", "Socp!Sec2026xK", "admin"));
        validateConfiguredCredential(env, violations, "socp.opensearch.username", List.of("", "admin"));

        validateMigrationRole(env, url, violations);

        if (isEnabled(env, "socp.opensearch.enabled")
                && "true".equalsIgnoreCase(env.getProperty("socp.opensearch.tls.insecure-skip-verify", "false"))) {
            violations.add("socp.opensearch.tls.insecure-skip-verify=true (production forbids trust-all TLS)");
        }
        String openSearchUrl = env.getProperty("socp.opensearch.url", "");
        if (isEnabled(env, "socp.opensearch.enabled") && !openSearchUrl.isBlank()
                && !openSearchUrl.toLowerCase(java.util.Locale.ROOT).startsWith("https://")) {
            violations.add("socp.opensearch.url must use HTTPS in production");
        }

        if (!violations.isEmpty()) {
            throw new IllegalStateException("【prod 启动校验失败】" + String.join("；", violations));
        }
        log.info("ProdGuard 通过：prod 模式启动校验无违规项");
    }

    /**
     * Correctness state shared by every replica must live in the Redis backend.
     * A per-process map is only ever a development convenience: with two gateway
     * replicas a memory backend makes logout effective for roughly half the
     * traffic and fails closed nowhere.
     */
    private static void requireSharedBackend(Environment env, List<String> violations, String key) {
        String backend = env.getProperty(key, "redis").trim();
        if (!"redis".equalsIgnoreCase(backend)) {
            violations.add(key + "=" + (backend.isBlank() ? "<blank>" : backend)
                    + " (production requires the shared redis backend)");
        }
    }

    /**
     * Flyway owns DDL, so the runtime connection must not double as the migration
     * role. Under prod + a postgresql datasource this guard fails fast when the
     * dedicated migration credentials are missing, reused from the runtime role,
     * or left on a local default — the application-side half of the contract the
     * no-default {@code ${SOCP_PG_MIGRATION_*}} binding and the Helm secretEnv pin
     * rely on (the runtime role cannot CREATE in the first place, so a silent
     * fallback there only surfaces as a hard boot failure; asserting here keeps the
     * diagnosis explicit and the two role identities provably separate).
     */
    private static void validateMigrationRole(Environment env, String datasourceUrl, List<String> violations) {
        if (!isEnabled(env, "spring.flyway.enabled")) {
            return;
        }
        String flywayUrl = env.getProperty("spring.flyway.url", "");
        boolean postgresql = isPostgres(datasourceUrl) || isPostgres(flywayUrl);
        if (!postgresql) {
            return;
        }
        String runtimeUser = env.getProperty("spring.datasource.username", "").trim();
        if (!env.containsProperty("spring.flyway.user") || env.getProperty("spring.flyway.user", "").trim().isEmpty()) {
            violations.add("spring.flyway.user must be an explicit dedicated migration role in production");
        } else {
            String migrationUser = env.getProperty("spring.flyway.user", "").trim();
            if (!runtimeUser.isEmpty() && migrationUser.equalsIgnoreCase(runtimeUser)) {
                violations.add("spring.flyway.user must differ from spring.datasource.username (separate migration role)");
            } else if (List.of("socp", "postgres").stream().anyMatch(migrationUser::equalsIgnoreCase)) {
                violations.add("spring.flyway.user uses a known local default instead of a dedicated migration role");
            }
        }
        if (!env.containsProperty("spring.flyway.password")
                || env.getProperty("spring.flyway.password", "").trim().isEmpty()) {
            violations.add("spring.flyway.password must be explicitly configured in production");
        }
    }

    private static boolean isPostgres(String jdbcUrl) {
        return jdbcUrl != null && jdbcUrl.toLowerCase(java.util.Locale.ROOT).startsWith("jdbc:postgresql:");
    }

    private static void validateConfiguredCredential(Environment env, List<String> violations,
                                                     String key, List<String> knownDefaults) {
        if (!env.containsProperty(key)) return;
        String value = env.getProperty(key, "");
        String normalized = value.trim();
        if (normalized.isBlank() || knownDefaults.stream().anyMatch(normalized::equals)) {
            violations.add(key + " is blank or uses a known development default");
        }
    }

    /**
     * Collector credentials are long-lived machine secrets rendered into collector
     * host configuration, so production demands the same discipline the JWT secret
     * already has: a minimum strength, an explicit end of validity, and a rotation
     * window that is actually shorter than the credential's own lifetime.
     */
    private static void validateCollectorCredentials(String encoded, List<String> violations) {
        for (String entry : encoded.split(";")) {
            String trimmed = entry.trim();
            if (trimmed.isEmpty()) continue;
            String[] parts = trimmed.split("\\|", 4);
            if (parts.length < 3) continue; // CollectorCredentialRegistry reports the shape error.
            String id = parts[0].trim();
            String secret = parts[2].trim();
            if (secret.isBlank() || List.of(DEMO_INGEST_TOKEN, DEMO_SERVICE_SECRET,
                    DEMO_JWT_SECRET, "admin", "password", "socp").stream()
                    .anyMatch(secret::equals)) {
                violations.add("socp.security.collector-credentials contains a known development default");
            }
            if (secret.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                    < CollectorCredentialRegistry.MIN_SECRET_BYTES) {
                violations.add("collector credential " + id + " is shorter than "
                        + CollectorCredentialRegistry.MIN_SECRET_BYTES
                        + " bytes (the same floor as socp.security.jwt-secret)");
            }
            if (parts.length < 4 || parts[3].isBlank()) {
                violations.add("collector credential " + id
                        + " has no notAfter; production credentials must expire so rotation is enforced");
                continue;
            }
            Instant notAfter;
            try {
                notAfter = CollectorCredentialRegistry.parseNotAfter(id, parts[3].trim());
            } catch (IllegalStateException invalid) {
                violations.add(invalid.getMessage());
                continue;
            }
            Duration remaining = Duration.between(Instant.now(), notAfter);
            if (remaining.isNegative() || remaining.isZero()) {
                violations.add("collector credential " + id + " expired at " + notAfter
                        + "; it can never authenticate again");
            } else if (remaining.toDays() > CollectorCredentialRegistry.MAX_VALIDITY_DAYS) {
                violations.add("collector credential " + id + " is valid for more than "
                        + CollectorCredentialRegistry.MAX_VALIDITY_DAYS
                        + " days; issue a shorter lifetime and rotate");
            }
        }
    }

    private static boolean collectorSecretsContain(String encoded, String candidate) {
        if (candidate == null || candidate.isBlank()) return false;
        byte[] expected = candidate.trim().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        for (String entry : encoded.split(";")) {
            String[] parts = entry.trim().split("\\|", 4);
            if (parts.length >= 3 && java.security.MessageDigest.isEqual(expected,
                    parts[2].trim().getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
                return true;
            }
        }
        return false;
    }

    private static boolean isEnabled(Environment env, String key) {
        return "true".equalsIgnoreCase(env.getProperty(key, "false"));
    }
}
