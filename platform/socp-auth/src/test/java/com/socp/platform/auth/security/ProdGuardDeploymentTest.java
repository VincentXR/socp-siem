package com.socp.platform.auth.security;

import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.core.io.FileSystemResource;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Real guard + real service YAML; no service boot or dependency connection. */
class ProdGuardDeploymentTest {
    private static final Path ROOT = repositoryRoot();
    private static final String COLLECTOR_TOKEN = "fixture-collector-secret-0123456789abcdef0123456789abcdef";

    @Test
    void everyProductWorkloadPassesTheRealGuardWithOnlyItsDeclaredSecrets() throws Exception {
        Map<String, Object> values = productValues();
        Map<String, Object> workloads = map(values.get("workloads"));
        assertEquals(16, workloads.size());
        for (String workload : workloads.keySet()) {
            StandardEnvironment environment = environment(values, workload, Map.of());
            assertDoesNotThrow(() -> new ProdGuard(environment), workload);
        }
    }

    @Test
    void omittedSearchAndHipsTokensRestoreRejectedDevelopmentDefaults() throws Exception {
        for (String workload : List.of("search-config-api", "search-config-worker", "hips-web")) {
            assertMissingSecretRejected(workload, "SOCP_INGEST_TOKEN", "socp.security.ingest-token");
        }
        for (String workload : List.of("search-config-api", "search-config-worker")) {
            assertMissingSecretRejected(workload, "SOCP_VECTOR_TOKEN", "socp.vector.token");
        }
    }

    @Test
    void reportObjectStoreCannotFallBackToDevelopmentCredentials() throws Exception {
        assertMissingSecretRejected("report-web", "SOCP_MINIO_SECRET", "socp.minio.secret-key");
        StandardEnvironment env = environment(productValues(), "report-web", Map.of());
        assertEquals("fixture-SOCP_MINIO_ACCESS-0123456789abcdef", env.getProperty("socp.minio.access-key"));
        assertEquals("https://minio.socp-data.svc.cluster.local:9000", env.getProperty("socp.minio.url"));
    }

    @Test
    void mismatchedVectorCollectorTokenIsRejected() throws Exception {
        StandardEnvironment env = environment(productValues(), "search-config-api", Map.of("SOCP_VECTOR_TOKEN", "unregistered-token"));
        IllegalStateException error = assertThrows(IllegalStateException.class, () -> new ProdGuard(env));
        assertTrue(error.getMessage().contains("must match a registered collector secret"));
    }

    private void assertMissingSecretRejected(String workload, String key, String expected) throws Exception {
        Map<String, Object> values = productValues();
        map(map(map(values.get("workloads")).get(workload)).get("secretEnv")).remove(key);
        StandardEnvironment env = environment(values, workload, Map.of());
        IllegalStateException error = assertThrows(IllegalStateException.class, () -> new ProdGuard(env));
        assertTrue(error.getMessage().contains(expected), error.getMessage());
    }

    private StandardEnvironment environment(Map<String, Object> values, String name, Map<String, Object> overrides) throws Exception {
        Map<String, Object> runtime = map(values.get("runtime"));
        Map<String, Object> workload = map(map(values.get("workloads")).get(name));
        Map<String, Object> variables = new LinkedHashMap<>(map(runtime.get("config")));
        variables.putAll(map(runtime.get("extraConfig")));
        variables.putAll(map(workload.get("env")));
        for (String key : map(workload.get("secretEnv")).keySet()) {
            variables.put(key, switch (key) {
                case "SOCP_PG_USER" -> "fixture_runtime";
                case "SOCP_PG_MIGRATION_USER" -> "fixture_migration";
                case "SOCP_COLLECTOR_CREDENTIALS" -> "fixture|tenant1|" + COLLECTOR_TOKEN + "|" + Instant.now().plus(7, ChronoUnit.DAYS);
                case "SOCP_INGEST_TOKEN", "SOCP_VECTOR_TOKEN" -> COLLECTOR_TOKEN;
                default -> "fixture-" + key + "-0123456789abcdef";
            });
        }
        if (map(workload.get("isolatedSecretEnv")).containsKey("SOCP_AUTH_SIGNING_JWK")) {
            variables.put("SOCP_AUTH_SIGNING_JWK", new RSAKeyGenerator(2048).keyID("fixture").generate().toJSONString());
        }
        // Non-secret, environment-owned issuer coordinates are release inputs.
        variables.put("SOCP_SECURITY_ISSUER_URI", "https://issuer.example.invalid");
        variables.put("SOCP_SECURITY_JWK_SET_URI", "https://issuer.example.invalid/jwks");
        variables.put("SOCP_AUTH_ISSUER", "https://issuer.example.invalid");
        variables.putAll(overrides);
        StandardEnvironment env = new StandardEnvironment();
        env.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        env.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        env.getPropertySources().addFirst(new SystemEnvironmentPropertySource("chart-environment", variables));
        env.setActiveProfiles("prod", "pg");
        String service = name.startsWith("search-config-") ? "search-config" : name.startsWith("detect-web-") ? "detect-web" : name;
        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        for (String profile : List.of("prod", "pg", "")) {
            Path path = ROOT.resolve("services/" + service + "/src/main/resources/application" + (profile.isEmpty() ? "" : "-" + profile) + ".yml");
            if (Files.exists(path)) {
                for (var source : loader.load(path.toString(), new FileSystemResource(path))) {
                    env.getPropertySources().addLast(source);
                }
            }
        }
        return env;
    }

    private Map<String, Object> productValues() throws IOException {
        Map<String, Object> values = new LinkedHashMap<>();
        for (String file : List.of("values.yaml", "values-production.yaml", "values-product.yaml")) {
            try (var input = Files.newInputStream(ROOT.resolve("deploy/helm/socp-core/" + file))) {
                merge(values, new Yaml().load(input));
            }
        }
        return values;
    }

    private static void merge(Map<String, Object> target, Map<String, Object> source) {
        source.forEach((key, value) -> {
            if (value instanceof Map<?, ?>) {
                Map<String, Object> nested = new LinkedHashMap<>(map(target.get(key)));
                merge(nested, map(value));
                target.put(key, nested);
            } else {
                target.put(key, value);
            }
        });
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> ? (Map<String, Object>) value : Map.of();
    }

    private static Path repositoryRoot() {
        Path path = Path.of("").toAbsolutePath();
        while (path != null && !Files.isRegularFile(path.resolve("deploy/helm/socp-core/values.yaml"))) {
            path = path.getParent();
        }
        if (path == null) throw new IllegalStateException("Cannot locate deployment fixtures");
        return path;
    }
}
