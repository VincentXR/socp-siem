package com.socp.platform.auth.security;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;

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
 *   <li>禁止默认采集凭据（dev-vector-token）</li>
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

        if ("memory".equalsIgnoreCase(env.getProperty("socp.ratelimit.backend", "memory"))) {
            violations.add("socp.ratelimit.backend=memory (production requires the shared Redis backend)");
        } else if (!"true".equalsIgnoreCase(env.getProperty("socp.ratelimit.fail-closed", "false"))) {
            violations.add("socp.ratelimit.fail-closed must be true in production");
        }

        if (!"kafka".equalsIgnoreCase(env.getProperty("socp.audit.sink", "memory"))) {
            violations.add("socp.audit.sink must be kafka in production");
        }

        if (!"true".equalsIgnoreCase(env.getProperty("socp.audit.fail-closed", "false"))) {
            violations.add("socp.audit.fail-closed must be true in production");
        }

        if ("api-gateway".equals(application)
                && !"true".equalsIgnoreCase(env.getProperty("socp.auth.cookie-secure", "false"))) {
            violations.add("socp.auth.cookie-secure=false (production session cookies require HTTPS)");
        }

        validateConfiguredCredential(env, violations, "spring.datasource.password", List.of("", "socp"));
        validateConfiguredCredential(env, violations, "socp.ck.password", List.of("", "socp"));
        validateConfiguredCredential(env, violations, "socp.minio.secret-key", List.of("", "Socp@2026"));
        validateConfiguredCredential(env, violations, "socp.auth.login-secret",
                List.of("", DEMO_JWT_SECRET));
        validateConfiguredCredential(env, violations, "socp.vector.token", List.of("", DEMO_INGEST_TOKEN));
        validateConfiguredCredential(env, violations, "socp.opensearch.password", List.of("", "Socp!Sec2026xK", "admin"));
        validateConfiguredCredential(env, violations, "socp.opensearch.username", List.of("", "admin"));

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

    private static void validateConfiguredCredential(Environment env, List<String> violations,
                                                     String key, List<String> knownDefaults) {
        if (!env.containsProperty(key)) return;
        String value = env.getProperty(key, "");
        String normalized = value.trim();
        if (normalized.isBlank() || knownDefaults.stream().anyMatch(normalized::equals)) {
            violations.add(key + " is blank or uses a known development default");
        }
    }

    private static void validateCollectorCredentials(String encoded, List<String> violations) {
        for (String entry : encoded.split(";")) {
            String[] parts = entry.trim().split("\\|", 3);
            if (parts.length != 3) continue; // CollectorCredentialRegistry reports the shape error.
            String secret = parts[2].trim();
            if (secret.isBlank() || List.of(DEMO_INGEST_TOKEN, DEMO_SERVICE_SECRET,
                    DEMO_JWT_SECRET, "admin", "password", "socp").stream()
                    .anyMatch(secret::equals)) {
                violations.add("socp.security.collector-credentials contains a known development default");
            }
        }
    }

    private static boolean collectorSecretsContain(String encoded, String candidate) {
        if (candidate == null || candidate.isBlank()) return false;
        byte[] expected = candidate.trim().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        for (String entry : encoded.split(";")) {
            String[] parts = entry.trim().split("\\|", 3);
            if (parts.length == 3 && java.security.MessageDigest.isEqual(expected,
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
