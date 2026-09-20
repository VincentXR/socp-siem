package com.socp.platform.auth.security;

import com.socp.platform.auth.config.SocpSecurityProperties;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CollectorCredentialRegistryTest {

    private static final java.time.ZoneId UTC = java.time.ZoneOffset.UTC;
    private static final String LIVE_A = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String LIVE_B = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
    private static final String FAR_FUTURE = "2999-01-01T00:00:00Z";
    private static final String PAST = "2000-01-01T00:00:00Z";

    @Test
    void authenticatesConfiguredSecretWithoutExposingIt() {
        CollectorCredentialRegistry registry = new CollectorCredentialRegistry(
                "vector-a|tenant-a|" + LIVE_A + ";falco-b|tenant-b|" + LIVE_B);

        Optional<CollectorCredentialRegistry.Identity> identity = registry.authenticate(LIVE_B);

        assertTrue(identity.isPresent());
        assertEquals("falco-b", identity.get().collectorId());
        assertEquals("tenant-b", identity.get().tenantId());
        assertTrue(registry.authenticate("wrong").isEmpty());
    }

    @Test
    void rejectsMalformedOrDuplicateConfiguration() {
        assertThrows(IllegalStateException.class,
                () -> new CollectorCredentialRegistry("broken-entry"));
        assertThrows(IllegalStateException.class,
                () -> new CollectorCredentialRegistry("a|tenant-a|same;b|tenant-b|same"));
        // An overlap without a deadline is an unbounded grace window.
        assertThrows(IllegalStateException.class,
                () -> new CollectorCredentialRegistry("a|tenant-a|" + LIVE_A + ";a|tenant-a|" + LIVE_B));
        assertThrows(IllegalStateException.class,
                () -> new CollectorCredentialRegistry(
                        "a|tenant-a|" + LIVE_A + "|" + FAR_FUTURE + ";a|tenant-a|" + LIVE_B));
    }

    @Test
    void acceptsOnlyTwoLiveSecretsPerCollector() {
        assertThrows(IllegalStateException.class,
                () -> new CollectorCredentialRegistry("a|tenant-a|" + LIVE_A + "|" + FAR_FUTURE
                        + ";a|tenant-a|" + LIVE_B + "|" + FAR_FUTURE
                        + ";a|tenant-a|cccccccccccccccccccccccccccccccc|" + FAR_FUTURE));
    }

    @Test
    void bothOverlappingSecretsAuthenticateInsideTheGraceWindow() {
        String sooner = Instant.now().plus(2, ChronoUnit.DAYS).truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString();
        CollectorCredentialRegistry registry = new CollectorCredentialRegistry(
                "a|tenant-a|" + LIVE_A + "|" + sooner + ";a|tenant-a|" + LIVE_B + "|" + FAR_FUTURE);

        // The old secret keeps working until its own deadline, which is what lets an
        // operator roll the fleet without an ingestion gap.
        assertEquals("a", registry.authenticate(LIVE_A).orElseThrow().collectorId());
        assertEquals("tenant-a", registry.authenticate(LIVE_B).orElseThrow().tenantId());
    }

    @Test
    void expiredSecretIsRejectedEvenThoughItIsStillConfigured() {
        CollectorCredentialRegistry registry = new CollectorCredentialRegistry(
                "a|tenant-a|" + LIVE_A + "|" + PAST);

        assertTrue(registry.authenticate(LIVE_A).isEmpty());
        assertTrue(registry.configuredCredentialCount() == 1);
        assertTrue(registry.daysToEarliestExpiry() < 0);
    }

    @Test
    void expiryAcceptsPlainDatesAndReportsTheNearestDeadline() {
        String nearer = java.time.LocalDate.now(UTC).plusDays(750).toString();
        String later = java.time.LocalDate.now(UTC).plusDays(790).toString();
        CollectorCredentialRegistry registry = new CollectorCredentialRegistry(
                "a|tenant-a|" + LIVE_A + "|" + nearer + ";b|tenant-b|" + LIVE_B + "|" + later);

        assertTrue(registry.authenticate(LIVE_A).isPresent());
        assertTrue(registry.authenticate(LIVE_B).isPresent());
        long days = registry.daysToEarliestExpiry();
        assertTrue(days >= 748 && days <= 750, "expected the nearer deadline, got " + days);
    }

    @Test
    void missingExpiryIsReportedAsNeverEnding() {
        CollectorCredentialRegistry registry = new CollectorCredentialRegistry(
                "a|tenant-a|" + LIVE_A);

        assertEquals(-1, registry.daysToEarliestExpiry());
    }

    @Test
    void invalidNotAfterFailsFastWithTheOffendingCollector() {
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> new CollectorCredentialRegistry("a|tenant-a|" + LIVE_A + "|next-friday"));

        assertTrue(failure.getMessage().contains("notAfter"));
        assertTrue(failure.getMessage().contains("a"));
    }

    @Test
    void reparsesWhenTheConfiguredValueChangesAndCachesOtherwise() {
        SocpSecurityProperties properties = new SocpSecurityProperties();
        properties.setCollectorCredentials("a|tenant-a|" + LIVE_A + "|" + FAR_FUTURE);
        CollectorCredentialRegistry registry = new CollectorCredentialRegistry(properties);

        assertTrue(registry.authenticate(LIVE_A).isPresent());
        assertTrue(registry.authenticate(LIVE_B).isEmpty());

        // Rotation: the operator replaces the secret in the environment and restarts.
        properties.setCollectorCredentials("a|tenant-a|" + LIVE_B + "|" + FAR_FUTURE);
        assertTrue(registry.authenticate(LIVE_B).isPresent());
        assertTrue(registry.authenticate(LIVE_A).isEmpty());
    }

    @Test
    void recordsUsageAndExpiryForRotationHygiene() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        CollectorCredentialRegistry registry = new CollectorCredentialRegistry(
                "a|tenant-a|" + LIVE_A + "|" + FAR_FUTURE, meters);

        assertTrue(registry.authenticate(LIVE_A).isPresent());
        assertTrue(registry.authenticate("nope").isEmpty());

        assertEquals(1.0, meters.get(CollectorCredentialRegistry.AUTH_METRIC)
                .tag("outcome", CollectorCredentialRegistry.OUTCOME_SUCCESS).counter().count());
        assertEquals(1.0, meters.get(CollectorCredentialRegistry.AUTH_METRIC)
                .tag("outcome", CollectorCredentialRegistry.OUTCOME_UNKNOWN).counter().count());
        org.junit.jupiter.api.Assertions.assertNull(meters.find(CollectorCredentialRegistry.AUTH_METRIC)
                .tag("outcome", CollectorCredentialRegistry.OUTCOME_EXPIRED).counter());
        Gauge expiry = meters.find(CollectorCredentialRegistry.EXPIRY_METRIC)
                .tag("collector", "a").gauge();
        assertTrue(expiry.value() > 365, "the credential is dated far out");
        assertTrue(registry.lastUsedEpochSeconds().get("a") > 0);
        // An unknown token must never invent a collector label.
        assertEquals(1, meters.find(CollectorCredentialRegistry.EXPIRY_METRIC).gauges().size());
    }

    @Test
    void countsAnExpiredPresentationSeparatelyFromAnUnknownToken() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        CollectorCredentialRegistry registry = new CollectorCredentialRegistry(
                "a|tenant-a|" + LIVE_A + "|" + Instant.now().minus(1, ChronoUnit.DAYS), meters);

        assertTrue(registry.authenticate(LIVE_A).isEmpty());

        assertEquals(1.0, meters.get(CollectorCredentialRegistry.AUTH_METRIC)
                .tag("outcome", CollectorCredentialRegistry.OUTCOME_EXPIRED).counter().count());
    }

    @Test
    void worksWithoutAMeterRegistry() {
        CollectorCredentialRegistry registry = new CollectorCredentialRegistry(
                new SocpSecurityProperties());
        registry.validateConfiguration();

        assertTrue(!registry.isConfigured());
        assertTrue(registry.authenticate(LIVE_A).isEmpty());
    }
}
