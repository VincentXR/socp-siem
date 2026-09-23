package com.socp.gateway.security;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Redis-backed auth limiter with a bounded development fallback.
 *
 * <p>The limiter is also the only control-plane witness for brute force against the
 * authentication endpoints: the gateway logs nothing else for {@code /auth/login} or
 * {@code /auth/service-token}, and this class already sees every attempt together
 * with the client address and the tried identity.</p>
 *
 * <p>Emitted: {@code socp_auth_attempt_total{kind,outcome}} with
 * {@code outcome=allowed|rejected} plus {@code socp_auth_attempt_success_total{kind}}
 * on the success reset. The caller only reports success, so credential failures are
 * {@code allowed} minus {@code success} - arithmetic on the scrape side instead of a
 * per-user label. {@code socp_auth_limiter_signal_total{signal}} carries the alertable
 * events ({@code limit-engaged}, {@code shared-account}, {@code credential-spray},
 * {@code redis-fallback}, {@code fail-closed}, {@code signal-budget-exhausted}) from a
 * fixed vocabulary, because an attacker must never inflate Prometheus cardinality
 * through a username. Each signal also writes one structured WARN line with the
 * subject and client address so a burst stays attributable; those lines are the only
 * ones one attacker can generate in volume, so they are budgeted per window per
 * replica ({@code socp.auth.rate-limit.signal-log-budget}) and the excess is counted
 * and summarised instead of logged.</p>
 *
 * <p>Counters are exact and cluster-wide once Prometheus sums the replicas. Peer
 * correlation is deliberately process-local and advisory: sharing it would need one
 * Redis set per tried identity or per source address, i.e. attacker-controlled key
 * cardinality on the busiest unauthenticated path, so a distributed burst surfaces as
 * a raised counter on several replicas rather than one line on one. Peer sets are
 * capped at {@code correlation-peers} members and both maps at
 * {@code socp.ratelimit.local-max-entries} windowed entries, so neither dimension can
 * grow without bound.</p>
 */
@Component
public class GatewayAuthAttemptLimiter implements AuthAttemptLimiter {

    private static final Logger log = LoggerFactory.getLogger(GatewayAuthAttemptLimiter.class);
    private static final DefaultRedisScript<Long> INCREMENT = new DefaultRedisScript<>("""
            local current = redis.call('INCR', KEYS[1])
            if current == 1 then redis.call('EXPIRE', KEYS[1], ARGV[1]) end
            return current
            """, Long.class);
    private static final String PREFIX = "socp:gateway:auth-attempt:";

    static final String ATTEMPT_METRIC = "socp_auth_attempt_total";
    static final String SUCCESS_METRIC = "socp_auth_attempt_success_total";
    static final String SIGNAL_METRIC = "socp_auth_limiter_signal_total";
    static final String LIMIT_ENGAGED = "limit-engaged";
    static final String SHARED_ACCOUNT = "shared-account";
    static final String CREDENTIAL_SPRAY = "credential-spray";
    static final String REDIS_FALLBACK = "redis-fallback";
    static final String FAIL_CLOSED = "fail-closed";
    static final String BUDGET_EXHAUSTED = "signal-budget-exhausted";

    private final ReactiveStringRedisTemplate redis;
    private final MeterRegistry registry;
    private final String backend;
    private final boolean failClosed;
    private final int loginPermits;
    private final int servicePermits;
    private final int windowSeconds;
    private final int correlationPeers;
    private final int signalLogBudget;
    @Value("${socp.ratelimit.local-max-entries:10000}")
    private int localMaxEntries = 10_000;

    private final ConcurrentHashMap<String, Window> local = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Peers> peers = new ConcurrentHashMap<>();
    private final AtomicLong signalWindowStart = new AtomicLong();
    private final AtomicInteger signalLogsEmitted = new AtomicInteger();
    private final AtomicInteger signalLogsSuppressed = new AtomicInteger();

    @Autowired
    public GatewayAuthAttemptLimiter(
            ReactiveStringRedisTemplate redis,
            MeterRegistry meterRegistry,
            @Value("${socp.ratelimit.backend:memory}") String backend,
            @Value("${socp.ratelimit.fail-closed:false}") boolean failClosed,
            @Value("${socp.auth.rate-limit.login-permits:5}") int loginPermits,
            @Value("${socp.auth.rate-limit.service-permits:10}") int servicePermits,
            @Value("${socp.auth.rate-limit.window-seconds:60}") int windowSeconds,
            @Value("${socp.auth.rate-limit.correlation-peers:5}") int correlationPeers,
            @Value("${socp.auth.rate-limit.signal-log-budget:60}") int signalLogBudget) {
        this.redis = redis;
        this.registry = meterRegistry;
        this.backend = backend;
        this.failClosed = failClosed;
        this.loginPermits = Math.max(1, loginPermits);
        this.servicePermits = Math.max(1, servicePermits);
        this.windowSeconds = Math.max(1, windowSeconds);
        // Below two peers the signal would fire on a single retry from a second
        // address, which is a roaming user rather than a distributed burst.
        this.correlationPeers = Math.max(2, correlationPeers);
        this.signalLogBudget = Math.max(1, signalLogBudget);
        this.signalWindowStart.set(currentWindowSlot());
    }

    /** Narrow constructor for embedders that only need the throttle itself. */
    public GatewayAuthAttemptLimiter(ReactiveStringRedisTemplate redis, MeterRegistry meterRegistry,
                                     String backend, boolean failClosed, int loginPermits,
                                     int servicePermits, int windowSeconds) {
        this(redis, meterRegistry, backend, failClosed, loginPermits, servicePermits, windowSeconds,
                5, 60);
    }

    @Override
    public Mono<Decision> acquire(String kind, String clientAddress, String identity) {
        int permits = permits(kind);
        if (!"redis".equalsIgnoreCase(backend)) {
            Outcome outcome = localAcquire(key(kind, clientAddress, identity), permits);
            observe(kind, clientAddress, identity, outcome.count(), permits, outcome.decision());
            return Mono.just(outcome.decision());
        }
        return redis.execute(INCREMENT, List.of(key(kind, clientAddress, identity)),
                        List.of(String.valueOf(windowSeconds)))
                .next()
                .map(count -> {
                    Decision decision = count <= permits
                            ? Decision.permit() : Decision.reject(windowSeconds);
                    observe(kind, clientAddress, identity, count, permits, decision);
                    return decision;
                })
                .switchIfEmpty(Mono.defer(() -> onRedisFailure(kind, clientAddress, identity,
                        permits, "empty Redis response")))
                .onErrorResume(failure -> onRedisFailure(kind, clientAddress, identity, permits,
                        failure.getMessage()));
    }

    @Override
    public Mono<Void> reset(String kind, String clientAddress, String identity) {
        local.remove(key(kind, clientAddress, identity));
        successCounter(kind).increment();
        if (!"redis".equalsIgnoreCase(backend)) return Mono.empty();
        return redis.delete(key(kind, clientAddress, identity)).then().onErrorResume(failure -> {
            log.warn("Unable to reset auth rate-limit key: {}", failure.getMessage());
            return Mono.empty();
        });
    }

    private Mono<Decision> onRedisFailure(String kind, String address, String identity, int permits,
                                          String reason) {
        if (failClosed) {
            // No authoritative count exists on this path, so only the rejection is
            // recorded; counting it as an allowed attempt would corrupt the failure
            // arithmetic described in the class documentation.
            rejected(kind).increment();
            signal(FAIL_CLOSED, "kind=" + kind + " subject=" + safe(identity)
                    + " client=" + safe(address) + " reason=" + reason);
            return Mono.just(Decision.reject(1));
        }
        signal(REDIS_FALLBACK, "kind=" + kind + " reason=" + reason);
        log.warn("Redis auth limiter unavailable; using local fallback: {}", reason);
        Outcome outcome = localAcquire(key(kind, address, identity), permits);
        observe(kind, address, identity, outcome.count(), permits, outcome.decision());
        return Mono.just(outcome.decision());
    }

    /**
     * Records one decided attempt. Never throws: the throttle verdict is already made
     * and a broken witness must not turn into an authentication failure.
     */
    private void observe(String kind, String address, String identity, long count, int permits,
                         Decision decision) {
        try {
            (decision.allowed() ? allowed(kind) : rejected(kind)).increment();
            // Exactly at permits + 1 the key crosses the line; later attempts inside
            // the same window are already throttled, so this fires once per key/window.
            if (count == permits + 1L) {
                signal(LIMIT_ENGAGED, "kind=" + kind + " subject=" + safe(identity)
                        + " client=" + safe(address) + " attempts=" + count + " permits=" + permits
                        + " window_seconds=" + windowSeconds);
            }
            correlate(kind, address, identity);
        } catch (RuntimeException witness) {
            log.warn("Auth attempt witness failed for kind={}: {}", kind, witness.toString());
        }
    }

    private void correlate(String kind, String address, String identity) {
        if (observePeer(scope("id", identity), digest(address))) {
            signal(SHARED_ACCOUNT, "kind=" + kind + " subject=" + safe(identity)
                    + " distinct_clients=" + correlationPeers + " last_client=" + safe(address));
        }
        if (observePeer(scope("ip", address), digest(identity))) {
            signal(CREDENTIAL_SPRAY, "kind=" + kind + " client=" + safe(address)
                    + " distinct_subjects=" + correlationPeers + " last_subject=" + safe(identity));
        }
    }

    /**
     * Adds one peer to the bounded windowed set for a scope.
     *
     * @return true only on the attempt that first reaches {@code correlation-peers} in
     *         the current window, so one burst produces one signal instead of one per
     *         later attempt
     */
    private boolean observePeer(String scopeKey, String peer) {
        long now = System.currentTimeMillis();
        long window = Duration.ofSeconds(windowSeconds).toMillis();
        boolean[] firstCrossing = new boolean[1];
        peers.compute(scopeKey, (ignored, current) -> {
            Peers entry = current == null || now >= current.expiresAtMillis
                    ? new Peers(now + window) : current;
            if (!entry.signalled && entry.members.size() < correlationPeers) {
                entry.members.add(peer);
            }
            if (!entry.signalled && entry.members.size() >= correlationPeers) {
                entry.signalled = true;
                firstCrossing[0] = true;
            }
            return entry;
        });
        cleanupPeers(now);
        return firstCrossing[0];
    }

    private void signal(String name, String details) {
        signalCounter(name).increment();
        if (withinLogBudget()) {
            log.warn("auth-limiter signal={} {}", name, details);
        }
    }

    /**
     * Per-window WARN budget. Signals keep counting when it is exhausted, so a spray of
     * thousands of identities cannot become a log-storm denial of service against the
     * component that is supposed to record it.
     */
    private boolean withinLogBudget() {
        long slot = currentWindowSlot();
        long start = signalWindowStart.get();
        if (slot != start && signalWindowStart.compareAndSet(start, slot)) {
            int suppressed = signalLogsSuppressed.getAndSet(0);
            signalLogsEmitted.set(0);
            if (suppressed > 0) {
                log.warn("auth-limiter signal={} suppressed={} window_seconds={}", BUDGET_EXHAUSTED,
                        suppressed, windowSeconds);
                signalCounter(BUDGET_EXHAUSTED).increment(suppressed);
            }
        }
        if (signalLogsEmitted.incrementAndGet() <= signalLogBudget) return true;
        signalLogsSuppressed.incrementAndGet();
        return false;
    }

    private long currentWindowSlot() {
        return System.currentTimeMillis() / 1000L / Math.max(1, windowSeconds);
    }

    private Counter signalCounter(String name) {
        return registry.counter(SIGNAL_METRIC, "signal", name);
    }

    private Counter allowed(String kind) {
        return registry.counter(ATTEMPT_METRIC, "kind", normalized(kind), "outcome", "allowed");
    }

    private Counter rejected(String kind) {
        return registry.counter(ATTEMPT_METRIC, "kind", normalized(kind), "outcome", "rejected");
    }

    private Counter successCounter(String kind) {
        return registry.counter(SUCCESS_METRIC, "kind", normalized(kind));
    }

    private int permits(String kind) {
        return "service".equals(normalized(kind)) ? servicePermits : loginPermits;
    }

    private Outcome localAcquire(String key, int permits) {
        long now = System.nanoTime();
        long window = Duration.ofSeconds(windowSeconds).toNanos();
        Window result = local.compute(key, (ignored, current) -> {
            if (current == null || now >= current.expiresAtNanos) {
                return new Window(1, now + window);
            }
            return new Window(current.count + 1, current.expiresAtNanos);
        });
        cleanupLocal(now);
        long retry = Math.max(1,
                Duration.ofNanos(Math.max(0, result.expiresAtNanos - now)).toSeconds());
        Decision decision = result.count <= permits
                ? Decision.permit() : Decision.reject(retry);
        return new Outcome(result.count, decision);
    }

    private void cleanupLocal(long now) {
        int limit = boundedMaxEntries();
        if (local.size() <= limit) return;
        local.entrySet().removeIf(entry -> now >= entry.getValue().expiresAtNanos);
        int excess = local.size() - limit;
        if (excess <= 0) return;
        local.entrySet().stream()
                .sorted(Comparator.comparingLong(entry -> entry.getValue().expiresAtNanos))
                .limit(excess)
                .forEach(entry -> local.remove(entry.getKey(), entry.getValue()));
    }

    private void cleanupPeers(long now) {
        int limit = boundedMaxEntries();
        if (peers.size() <= limit) return;
        peers.entrySet().removeIf(entry -> now >= entry.getValue().expiresAtMillis);
        int excess = peers.size() - limit;
        if (excess <= 0) return;
        peers.entrySet().stream()
                .sorted(Comparator.comparingLong(entry -> entry.getValue().expiresAtMillis))
                .limit(excess)
                .forEach(entry -> peers.remove(entry.getKey(), entry.getValue()));
    }

    private int boundedMaxEntries() {
        return Math.max(1, Math.min(1_000_000, localMaxEntries));
    }

    private static String key(String kind, String address, String identity) {
        return PREFIX + normalized(kind) + ':' + digest(normalized(address) + '\u0000'
                + normalized(identity));
    }

    private static String scope(String dimension, String value) {
        return dimension + ':' + digest(value);
    }

    private static String digest(String value) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(normalized(value).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash).substring(0, 32);
        } catch (Exception impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    /** Log field kept short and free of separators so the line stays parseable. */
    private static String safe(String value) {
        if (value == null || value.isBlank()) return "unknown";
        String trimmed = value.trim().replace('=', '-').replace(' ', '_');
        return trimmed.length() <= 96 ? trimmed : trimmed.substring(0, 96);
    }

    private static String normalized(String value) {
        return value == null || value.isBlank() ? "unknown" : value.trim().toLowerCase();
    }

    private record Window(int count, long expiresAtNanos) {
    }

    /** Windowed peer set; {@code signalled} keeps the threshold edge single-shot. */
    private static final class Peers {
        private final Set<String> members = new LinkedHashSet<>();
        private final long expiresAtMillis;
        private boolean signalled;

        private Peers(long expiresAtMillis) {
            this.expiresAtMillis = expiresAtMillis;
        }
    }

    private record Outcome(long count, Decision decision) {
    }
}
