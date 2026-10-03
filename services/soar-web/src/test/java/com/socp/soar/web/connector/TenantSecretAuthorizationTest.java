package com.socp.soar.web.connector;

import com.socp.soar.web.config.SoarSecretProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TenantSecretAuthorizationTest {
    @TempDir Path mount;
    @Test void deniesForeignOrUngrantableReferencesBeforeProviderLookup() {
        var grants = new TenantSecretAuthorizer("{\"tenant-a\":[\"secret://soc/edr\",\"k8s://platform/edr/token\",\"vault://kv/data/team/edr#token\"]}");
        AtomicInteger reads = new AtomicInteger();
        SecretResolver resolver = new SecretResolver() {
            public Optional<String> resolve(String reference) { reads.incrementAndGet(); return Optional.of("fixture-value"); }
            public boolean isAuthorized(String tenant, String reference) { return grants.allows(tenant, reference); }
        };
        for (String reference : new String[] { "secret://soc/edr", "k8s://platform/edr/token", "vault://kv/data/team/edr#token" }) {
            assertThat(context("tenant-a", reference, resolver).resolveSecret("auth")).isEqualTo("fixture-value");
            assertThatThrownBy(() -> context("tenant-b", reference, resolver).resolveSecret("auth"))
                    .hasMessageContaining("SOAR_SECRET_RESOLUTION_FAILED");
        }
        assertThat(reads).hasValue(3);
        assertThat(grants.allows("tenant-a", "env://PATH")).isFalse();
        assertThat(grants.allows("tenant-a", "secret://soc/../edr")).isFalse();
        assertThat(grants.allows("tenant-a", "secret://SOC/edr")).isFalse();
        assertThat(new TenantSecretAuthorizer("{}").allows("tenant-a", "secret://soc/edr")).isFalse();
        assertThatThrownBy(() -> new TenantSecretAuthorizer("{\"tenant-a\":[\"k8s://platform/../token\"]}"))
                .isInstanceOf(IllegalArgumentException.class);
    }
    @Test void projectedKubernetesSecretsResolveOnlyForExactGrantedTenant() throws Exception {
        Path token = mount.resolve("platform/edr/token"); Files.createDirectories(token.getParent());
        Files.writeString(token, "rotating-fixture");
        var properties = new SoarSecretProperties(); properties.setKubernetesMountPath(mount.toString());
        properties.setAllowEnvironmentFallback(false);
        properties.setTenantGrants("{\"tenant-a\":[\"k8s://platform/edr/token\"]}");
        var resolver = new KubernetesSecretResolver(properties);
        assertThat(resolver.resolveForTenant("tenant-a", "k8s://platform/edr/token")).contains("rotating-fixture");
        assertThat(resolver.resolveForTenant("tenant-b", "k8s://platform/edr/token")).isEmpty();
        Files.writeString(token, "rotated");
        assertThat(resolver.resolveForTenant("tenant-a", "k8s://platform/edr/token")).contains("rotated");
    }
    private ConnectionContext context(String tenant, String reference, SecretResolver resolver) {
        return new ConnectionContext(tenant, "connection", 1, "http.webhook", "https://hooks.example.test",
                Map.of(), Map.of("auth", reference), resolver, Duration.ofSeconds(1));
    }
}
