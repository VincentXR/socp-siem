package com.socp.soar.web.connector;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Coverage for the development-safe env:// and secret:// resolver. The class
 * reads System.getenv directly (cannot be seeded from a JVM test), so the hit
 * paths are asserted against guaranteed-present variables (PATH) and the
 * fallback/miss paths against deliberately unused variable names.
 */
class EnvironmentSecretResolverCoverageTest {

    private final EnvironmentSecretResolver resolver = new EnvironmentSecretResolver();

    @Test
    void nullAndBlankReferencesProduceEmpty() {
        assertThat(resolver.resolve(null)).isEmpty();
        assertThat(resolver.resolve("")).isEmpty();
        assertThat(resolver.resolve("   ")).isEmpty();
    }

    @Test
    void unknownSchemeProducesEmpty() {
        assertThat(resolver.resolve("file://secrets")).isEmpty();
        assertThat(resolver.resolve("plain-value")).isEmpty();
    }

    @Test
    void envReferenceWithInvalidKeyProducesEmpty() {
        assertThat(resolver.resolve("env://1BAD-KEY")).isEmpty();
        assertThat(resolver.resolve("env://bad key")).isEmpty();
    }

    @Test
    void envReferenceResolvesFromEnvironmentVariable() {
        // PATH is set in every Windows/POSIX environment.
        Optional<String> resolved = resolver.resolve("env://PATH");

        assertThat(resolved).isPresent();
        assertThat(resolved.get()).isNotBlank();
    }

    @Test
    void envReferenceForMissingVariableProducesEmpty() {
        assertThat(resolver.resolve("env://SOAR_DEFINITELY_UNSET_SECRET_XYZ")).isEmpty();
    }

    @Test
    void secretReferenceWithInvalidKeyProducesEmpty() {
        assertThat(resolver.resolve("secret://has space")).isEmpty();
        assertThat(resolver.resolve("secret://bad.dot!!")).isEmpty();
    }

    @Test
    void secretReferenceResolvesDirectEnvironmentVariable() {
        // Direct env lookup succeeds for plain identifiers without the prefix.
        Optional<String> resolved = resolver.resolve("secret://PATH");

        assertThat(resolved).isPresent();
        assertThat(resolved.get()).isNotBlank();
    }

    @Test
    void secretReferenceFallsBackToNormalizedPrefixedVariable() {
        // "path.sub.key" fails the direct identifier match, so the resolver
        // normalizes to SOAR_SECRET_PATH_SUB_KEY which is never configured in
        // the test environment; the prefixed fallback returns empty.
        assertThat(resolver.resolve("secret://path.sub.key")).isEmpty();
        assertThat(resolver.resolve("secret://some/path-key")).isEmpty();
    }
}
