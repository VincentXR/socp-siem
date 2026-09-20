package com.socp.platform.auth.security;

import com.socp.platform.auth.config.SocpSecurityProperties;
import com.socp.platform.tenant.context.TenantContext;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Resolves collector credentials into a trusted collector and tenant identity.
 *
 * <p>The compact configuration format is deliberately environment-friendly:
 * {@code collector-id|tenant-id|secret[|notAfter];another-id|tenant-id|secret}.
 * {@code notAfter} is an ISO-8601 instant or date (a date means its start of day in
 * UTC) and is the credential's hard end: an expired entry never authenticates, so a
 * rotation is finished by its deadline instead of depending on someone remembering to
 * restart every replica. Secrets are never logged and comparisons are constant-time.
 * A single legacy ingest token remains available for local development, but production
 * validation requires at least one registered collector credential.</p>
 *
 * <h2>Rotation grace period</h2>
 * <p>The same collector id may appear up to {@value #MAX_LIVE_SECRETS_PER_COLLECTOR}
 * times with different secrets, and every entry of such a pair must carry an explicit
 * {@code notAfter}. That is the double-live window an operator needs to roll a Vector
 * TOML out to a fleet without an ingestion gap, while the deadline bounds how long the
 * replaced secret stays valid. Re-using one secret for two collectors stays a hard
 * configuration error: it would make the resolved identity ambiguous.</p>
 *
 * <h2>What the registry makes observable</h2>
 * <p>Every authentication attempt is counted in {@code socp_collector_auth_total} with
 * an outcome from a fixed vocabulary ({@code success}, {@code expired},
 * {@code unknown}), so a revoked-but-still-trying collector and a token-guessing
 * attacker are both visible without adding an attacker-controlled label. Per
 * configured collector id the registry also exposes
 * {@code socp_collector_credential_expires_in_days} and
 * {@code socp_collector_credential_days_since_last_use}: the first makes "rotate
 * before it lapses" an alert instead of a habit, the second finds credentials that
 * nobody is using any more and should simply be removed. Both gauges are read from
 * process state, so they restart with the replica and describe the replica that
 * serves them; they are rotation hygiene, not correctness state. Startup logs one
 * WARN per credential that is weak, undated, already expired or expiring within
 * {@value #ROTATION_WARNING_DAYS} days, and {@link ProdGuard} turns the same checks
 * into a production boot failure.</p>
 */
public class CollectorCredentialRegistry {

    public static final String COLLECTOR_ID_ATTRIBUTE =
            CollectorCredentialRegistry.class.getName() + ".collectorId";

    /** Rotation overlap: never more than two live secrets for one collector id. */
    public static final int MAX_LIVE_SECRETS_PER_COLLECTOR = 2;
    /** Same floor the HS256 jwt-secret already has. */
    public static final int MIN_SECRET_BYTES = 32;
    public static final long ROTATION_WARNING_DAYS = 30;
    /** A credential that never expires is not rotated; cap the validity it may claim. */
    public static final long MAX_VALIDITY_DAYS = 366;

    static final String OUTCOME_SUCCESS = "success";
    static final String OUTCOME_EXPIRED = "expired";
    static final String OUTCOME_UNKNOWN = "unknown";
    static final String AUTH_METRIC = "socp_collector_auth_total";
    static final String EXPIRY_METRIC = "socp_collector_credential_expires_in_days";
    static final String LAST_USE_METRIC = "socp_collector_credential_days_since_last_use";

    private static final Logger log = LoggerFactory.getLogger(CollectorCredentialRegistry.class);
    private static final String ID_PATTERN = "[A-Za-z0-9][A-Za-z0-9._-]{0,63}";

    private final SocpSecurityProperties properties;
    private final MeterRegistry meters;
    private final Map<String, CollectorState> states = new ConcurrentHashMap<>();
    /** Memoized parse: the raw string is the cache key, so a config change is self-invalidating. */
    private volatile Snapshot snapshot;

    @Autowired
    public CollectorCredentialRegistry(SocpSecurityProperties properties,
                                       ObjectProvider<MeterRegistry> meterRegistry) {
        this(properties, meterRegistry == null ? null : meterRegistry.getIfAvailable());
    }

    public CollectorCredentialRegistry(SocpSecurityProperties properties) {
        this.properties = properties;
        this.meters = null;
    }

    CollectorCredentialRegistry(SocpSecurityProperties properties, MeterRegistry meters) {
        this.properties = properties;
        this.meters = meters;
    }

    CollectorCredentialRegistry(String encoded) {
        this(encoded, null);
    }

    CollectorCredentialRegistry(String encoded, MeterRegistry meters) {
        this.properties = null;
        this.meters = meters;
        // A fixed string cannot change at runtime, so the snapshot is built once.
        Snapshot parsed = parse(encoded);
        bindDeadlines(parsed.credentials());
        this.snapshot = parsed;
    }

    @PostConstruct
    void validateConfiguration() {
        // Fail fast on malformed configuration instead of waiting for the
        // first collector request to discover it, then state the rotation posture.
        logPosture(snapshot().credentials());
    }

    /** Returns the trusted identity for a bearer secret, if one is configured and live. */
    public Optional<Identity> authenticate(String secret) {
        if (secret == null || secret.isBlank()) return Optional.empty();
        Snapshot current = snapshot();
        Identity matched = null;
        Credential matchedCredential = null;
        for (Credential credential : current.credentials()) {
            // Every entry is compared, including expired ones: skipping them early would
            // make the elapsed time depend on which secret was retired.
            if (constantTimeEquals(credential.secret(), secret)) {
                matched = new Identity(credential.id(), credential.tenantId());
                matchedCredential = credential;
            }
        }
        if (matchedCredential == null) {
            count(OUTCOME_UNKNOWN);
            return Optional.empty();
        }
        if (matchedCredential.isExpiredAt(Instant.now())) {
            count(OUTCOME_EXPIRED);
            logExpired(matchedCredential);
            return Optional.empty();
        }
        count(OUTCOME_SUCCESS);
        state(matched.collectorId()).lastUsedEpochSecond.set(Instant.now().getEpochSecond());
        return Optional.of(matched);
    }

    public boolean isConfigured() {
        return !snapshot().credentials().isEmpty();
    }

    /** Live credentials as configured; never exposes a secret. */
    public int configuredCredentialCount() {
        return snapshot().credentials().size();
    }

    /**
     * Days until the earliest configured expiry, or {@code -1} when no entry carries a
     * {@code notAfter}. Already-expired entries report a negative value.
     */
    public long daysToEarliestExpiry() {
        long best = Long.MAX_VALUE;
        for (Credential credential : snapshot().credentials()) {
            Instant expiresAt = credential.expiresAt();
            if (expiresAt == null) return -1;
            best = Math.min(best, Duration.between(Instant.now(), expiresAt).toDays());
        }
        return best == Long.MAX_VALUE ? -1 : best;
    }

    /**
     * Epoch seconds of the last accepted authentication per collector id, or
     * {@code 0} when this replica has not served that collector yet. Process-local by
     * design: it is a rotation hygiene read-out, not distributed state.
     */
    public Map<String, Long> lastUsedEpochSeconds() {
        Map<String, Long> result = new java.util.TreeMap<>();
        states.forEach((id, state) -> result.put(id, state.lastUsedEpochSecond.get()));
        return Map.copyOf(result);
    }

    private Snapshot snapshot() {
        if (properties == null) return snapshot;
        String raw = properties.getCollectorCredentials();
        Snapshot current = snapshot;
        if (current != null && current.matches(raw)) return current;
        // Unit tests mutate SocpSecurityProperties directly and there is no
        // @RefreshScope in the repository, so re-parsing on a changed value is both
        // the correctness hook and the memoization.
        Snapshot parsed = parse(raw);
        bindDeadlines(parsed.credentials());
        this.snapshot = parsed;
        return parsed;
    }

    /** Keeps the per-collector expiry gauge aligned with the parsed configuration. */
    private void bindDeadlines(List<Credential> credentials) {
        for (Credential credential : credentials) {
            state(credential.id()).expiresAtEpochSecond.set(credential.expiresAt() == null
                    ? -1 : credential.expiresAt().getEpochSecond());
        }
    }

    private static Snapshot parse(String encoded) {
        List<Credential> result = new ArrayList<>();
        Set<String> seenSecrets = new LinkedHashSet<>();
        if (encoded != null && !encoded.isBlank()) {
            for (String item : encoded.split(";")) {
                String value = item.trim();
                if (value.isBlank()) continue;
                String[] parts = value.split("\\|", 4);
                if (parts.length < 3 || parts[0].isBlank() || parts[1].isBlank() || parts[2].isBlank()) {
                    throw new IllegalStateException(
                            "socp.security.collector-credentials must use id|tenant|secret[|notAfter] entries");
                }
                String id = parts[0].trim();
                String tenant = parts[1].trim();
                String secret = parts[2].trim();
                Instant expiresAt = parts.length == 4 && !parts[3].isBlank()
                        ? parseNotAfter(id, parts[3].trim()) : null;
                if (!id.matches(ID_PATTERN)) {
                    throw new IllegalStateException("collector credential id is invalid: " + id);
                }
                if (!TenantContext.isValid(tenant)) {
                    throw new IllegalStateException("collector credential tenant is invalid: " + tenant);
                }
                String secretKey = digest(secret);
                if (!seenSecrets.add(secretKey)) {
                    throw new IllegalStateException("duplicate collector credential secret");
                }
                List<Credential> sameId = result.stream().filter(existing -> existing.id().equals(id)).toList();
                if (sameId.size() + 1 > MAX_LIVE_SECRETS_PER_COLLECTOR) {
                    throw new IllegalStateException("collector credential id " + id + " has more than "
                            + MAX_LIVE_SECRETS_PER_COLLECTOR + " live secrets; the rotation grace period "
                            + "allows exactly one replacement");
                }
                for (Credential existing : sameId) {
                    if (existing.expiresAt() == null || expiresAt == null) {
                        throw new IllegalStateException("collector credential id " + id
                                + " overlaps two secrets, so every entry of it must carry notAfter; "
                                + "an unbounded grace window is not a rotation");
                    }
                }
                result.add(new Credential(id, tenant, secret, expiresAt));
            }
        }
        return new Snapshot(encoded, List.copyOf(result));
    }

    /** Shared with {@link ProdGuard} so the boot assertion and the runtime agree on the format. */
    static Instant parseNotAfter(String id, String value) {
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException ignored) {
            // fall through to the date form
        }
        try {
            return LocalDate.parse(value).atStartOfDay(java.time.ZoneOffset.UTC).toInstant();
        } catch (DateTimeParseException invalid) {
            throw new IllegalStateException("collector credential notAfter for " + id
                    + " must be an ISO-8601 instant or date, for example 2026-12-31T00:00:00Z: " + value);
        }
    }

    private void logPosture(List<Credential> credentials) {
        Instant now = Instant.now();
        for (Credential credential : credentials) {
            if (credential.secret().getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
                log.warn("collector credential {} is shorter than the {} byte production floor; rotate it",
                        credential.id(), MIN_SECRET_BYTES);
            }
            if (credential.expiresAt() == null) {
                log.warn("collector credential {} has no notAfter; it stays valid until an operator "
                        + "removes it and restarts every replica", credential.id());
                continue;
            }
            long days = Duration.between(now, credential.expiresAt()).toDays();
            if (days < 0) {
                logExpired(credential);
            } else if (days <= ROTATION_WARNING_DAYS) {
                log.warn("collector credential {} expires in {} days; rotate it before it lapses",
                        credential.id(), days);
            }
        }
    }

    /** One line per collector per day: an expired credential retries on every push. */
    private void logExpired(Credential credential) {
        CollectorState state = state(credential.id());
        long today = Instant.now().getEpochSecond() / Duration.ofDays(1).toSeconds();
        if (state.expiryLogDay.getAndSet(today) != today) {
            log.warn("collector credential {} expired at {}; the collector is still presenting it, so "
                    + "its events are being rejected", credential.id(), credential.expiresAt());
        }
    }

    private CollectorState state(String collectorId) {
        return states.computeIfAbsent(collectorId, id -> {
            CollectorState created = new CollectorState();
            if (meters != null) {
                Gauge.builder(EXPIRY_METRIC, created, CollectorState::daysToExpiry)
                        .tag("collector", id).register(meters);
                Gauge.builder(LAST_USE_METRIC, created, CollectorState::daysSinceLastUse)
                        .tag("collector", id).register(meters);
            }
            return created;
        });
    }

    /** Counts with a fixed outcome vocabulary; a no-op when metrics are unavailable. */
    private void count(String outcome) {
        if (meters == null) return;
        meters.counter(AUTH_METRIC, "outcome", outcome).increment();
    }

    private static boolean constantTimeEquals(String expected, String actual) {
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8));
    }

    private static String digest(String value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private record Snapshot(String raw, List<Credential> credentials) {
        boolean matches(String other) {
            return raw == null ? other == null : raw.equals(other);
        }
    }

    /** A configured collector secret, with its optional hard end. */
    public record Credential(String id, String tenantId, String secret, Instant expiresAt) {
        boolean isExpiredAt(Instant now) {
            return expiresAt != null && !expiresAt.isAfter(now);
        }
    }

    public record Identity(String collectorId, String tenantId) {
    }

    private static final class CollectorState {
        private final AtomicLong lastUsedEpochSecond = new AtomicLong();
        private final AtomicLong expiresAtEpochSecond = new AtomicLong(-1);
        private final AtomicLong expiryLogDay = new AtomicLong(-1);

        private double daysToExpiry() {
            long epochSecond = expiresAtEpochSecond.get();
            if (epochSecond <= 0) return -1;
            return Duration.between(Instant.now(), Instant.ofEpochSecond(epochSecond)).toDays();
        }

        private double daysSinceLastUse() {
            long epochSecond = lastUsedEpochSecond.get();
            if (epochSecond <= 0) return -1;
            return Duration.between(Instant.ofEpochSecond(epochSecond), Instant.now()).toDays();
        }
    }
}
