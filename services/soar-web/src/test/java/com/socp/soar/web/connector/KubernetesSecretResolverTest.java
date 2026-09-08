package com.socp.soar.web.connector;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.assertj.core.api.Assertions.assertThat;

class KubernetesSecretResolverTest {

    @TempDir
    Path mount;

    @Test
    void readsProjectedSecretAndSeesRotationOnNextLookup() throws Exception {
        Path file = mount.resolve("platform").resolve("edr").resolve("token");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "first-token", StandardCharsets.UTF_8);
        KubernetesSecretResolver resolver = new KubernetesSecretResolver(mount.toString());

        assertThat(resolver.resolve("k8s://platform/edr/token")).contains("first-token");

        Files.writeString(file, "rotated-token", StandardCharsets.UTF_8);
        assertThat(resolver.resolve("k8s://platform/edr/token")).contains("rotated-token");
    }

    @Test
    void rejectsTraversalMalformedAndOversizedReferences() throws Exception {
        KubernetesSecretResolver resolver = new KubernetesSecretResolver(mount.toString());
        assertThat(resolver.resolve("k8s://platform/../token")).isEmpty();
        assertThat(resolver.resolve("k8s://platform/edr/token/extra")).isEmpty();
        assertThat(resolver.resolve("k8s://platform/edr/../token")).isEmpty();
        assertThat(resolver.resolve("k8s://platform/edr/token")).isEmpty();

        Path file = mount.resolve("platform").resolve("edr").resolve("large");
        Files.createDirectories(file.getParent());
        Files.write(file, new byte[64 * 1024 + 1]);
        assertThat(resolver.resolve("k8s://platform/edr/large")).isEmpty();
    }

    @Test
    void keepsEnvironmentCompatibilityReferences() {
        KubernetesSecretResolver resolver = new KubernetesSecretResolver(mount.toString());
        assertThat(resolver.resolve("unsupported://secret")).isEmpty();
        assertThat(resolver.resolve((String) null)).isEmpty();
        assertThat(resolver.resolve("env://SOAR_MISSING_SECRET_FOR_TEST")).isEmpty();
    }

    @Test
    void canDisableEnvironmentFallbackForProduction() {
        KubernetesSecretResolver resolver = new KubernetesSecretResolver(mount.toString(), false);
        assertThat(resolver.resolve("env://PATH")).isEmpty();
    }
}
