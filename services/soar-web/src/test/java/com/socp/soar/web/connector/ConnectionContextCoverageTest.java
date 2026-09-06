package com.socp.soar.web.connector;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConnectionContextCoverageTest {

    private static final SecretResolver RESOLVER = reference -> Optional.of("s3cret-" + reference);

    @Test
    void missingTimeoutAndHostsFallBackToSafeDefaults() {
        ConnectionContext context = new ConnectionContext("tenant-a", "conn-1", 3, "endpoint",
                "https://edr.example.com/api", null, null, RESOLVER, null);

        assertThat(context.timeout()).isEqualTo(Duration.ofSeconds(30));
        assertThat(context.allowedHosts()).isEmpty();
        assertThat(context.config()).isEmpty();
        assertThat(context.secretRefs()).isEmpty();
        assertThat(context.tenantId()).isEqualTo("tenant-a");
        assertThat(context.connectionId()).isEqualTo("conn-1");
        assertThat(context.revision()).isEqualTo(3);
        assertThat(context.connectorType()).isEqualTo("endpoint");
        assertThat(context.endpoint()).isEqualTo("https://edr.example.com/api");
    }

    @Test
    void compactConstructorKeepsExplicitHostsAndTimeout() {
        ConnectionContext context = new ConnectionContext("tenant-a", "conn-1", 1, "endpoint",
                "https://edr.example.com/api", Map.of("tls", "strict"), Map.of("auth", "secret://vault/edr"),
                RESOLVER, Duration.ofSeconds(5));

        assertThat(context.timeout()).isEqualTo(Duration.ofSeconds(5));
        assertThat(context.config()).containsEntry("tls", "strict");
        assertThat(context.secretRefs()).containsEntry("auth", "secret://vault/edr");
        assertThat(context.allowedHosts()).isEmpty();
    }

    @Test
    void fullConstructorStoresAllowlistAndImmutableMaps() {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("tls", "strict");
        ConnectionContext context = new ConnectionContext("tenant-a", "conn-1", 1, "endpoint",
                "https://edr.example.com/api", config, Map.of("auth", "secret://vault/edr"),
                RESOLVER, Duration.ofSeconds(10), List.of("edr.example.com"));

        assertThat(context.allowedHosts()).containsExactly("edr.example.com");
        assertThatThrownBy(() -> context.allowedHosts().add("evil.example"))
                .isInstanceOf(UnsupportedOperationException.class);

        config.put("mutated", true);
        assertThat(context.config()).doesNotContainKey("mutated");
        assertThatThrownBy(() -> context.config().put("x", "y"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void resolveSecretReturnsNullWithoutAReference() {
        ConnectionContext context = new ConnectionContext("tenant-a", "conn-1", 1, "endpoint",
                "https://edr.example.com/api", Map.of(), Map.of("auth", "  "), RESOLVER, Duration.ofSeconds(5));

        assertThat(context.secretRefs().get("missing")).isNull();
        assertThat(context.resolveSecret("missing")).isNull();
        assertThat(context.resolveSecret("auth")).isNull();
    }

    @Test
    void resolveSecretReturnsValueWhenTheResolverSucceeds() {
        ConnectionContext context = new ConnectionContext("tenant-a", "conn-1", 1, "endpoint",
                "https://edr.example.com/api", Map.of(), Map.of("auth", "secret://vault/edr"),
                RESOLVER, Duration.ofSeconds(5));

        assertThat(context.resolveSecret("auth")).isEqualTo("s3cret-secret://vault/edr");
    }

    @Test
    void resolveSecretFailsClosedWhenTheReferenceCannotBeResolved() {
        ConnectionContext context = new ConnectionContext("tenant-a", "conn-1", 1, "endpoint",
                "https://edr.example.com/api", Map.of(), Map.of("auth", "secret://vault/edr"),
                reference -> Optional.empty(), Duration.ofSeconds(5));

        assertThatThrownBy(() -> context.resolveSecret("auth"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SOAR_SECRET_RESOLUTION_FAILED")
                .hasMessageContaining("auth");
    }

    @Test
    void connectionTestResultFactoriesMarkHealthAndTimestamp() {
        ConnectionTestResult ok = ConnectionTestResult.ok(42L, Map.of("status", 200));
        assertThat(ok.healthy()).isTrue();
        assertThat(ok.status()).isEqualTo("HEALTHY");
        assertThat(ok.errorCode()).isNull();
        assertThat(ok.errorMessage()).isNull();
        assertThat(ok.durationMs()).isEqualTo(42L);
        assertThat(ok.details()).containsEntry("status", 200);
        assertThat(ok.testedAt()).isNotNull();
        assertThatThrownBy(() -> ok.details().put("x", "y"))
                .isInstanceOf(UnsupportedOperationException.class);

        ConnectionTestResult failed = ConnectionTestResult.failed("SOAR_EGRESS_DENIED", "blocked", 7L);
        assertThat(failed.healthy()).isFalse();
        assertThat(failed.status()).isEqualTo("UNHEALTHY");
        assertThat(failed.errorCode()).isEqualTo("SOAR_EGRESS_DENIED");
        assertThat(failed.errorMessage()).isEqualTo("blocked");
        assertThat(failed.durationMs()).isEqualTo(7L);
        assertThat(failed.details()).isEmpty();
        assertThat(failed.testedAt()).isNotNull();
    }
}
