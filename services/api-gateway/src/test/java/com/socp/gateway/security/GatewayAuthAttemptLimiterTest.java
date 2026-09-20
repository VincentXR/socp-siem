package com.socp.gateway.security;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import reactor.core.publisher.Flux;
import java.util.List;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GatewayAuthAttemptLimiterTest {

    private static final int LOGIN_PERMITS = 2;

    @Test
    void localBackendLimitsByAddressAndIdentityAndCanReset() {
        MeterRegistry registry = new SimpleMeterRegistry();
        GatewayAuthAttemptLimiter limiter = new GatewayAuthAttemptLimiter(
                mock(ReactiveStringRedisTemplate.class), registry, "memory", false,
                LOGIN_PERMITS, 1, 60);

        assertThat(limiter.acquire("login", "127.0.0.1", "alice").block().allowed()).isTrue();
        assertThat(limiter.acquire("login", "127.0.0.1", "alice").block().allowed()).isTrue();
        assertThat(limiter.acquire("login", "127.0.0.1", "alice").block().allowed()).isFalse();
        assertThat(limiter.acquire("login", "127.0.0.1", "bob").block().allowed()).isTrue();

        limiter.reset("login", "127.0.0.1", "alice").block();
        assertThat(limiter.acquire("login", "127.0.0.1", "alice").block().allowed()).isTrue();

        // The witness must separate throttled attempts from the ones that were answered,
        // because credential failures are derived as allowed minus success.
        assertThat(registry.get(GatewayAuthAttemptLimiter.ATTEMPT_METRIC)
                .tag("kind", "login").tag("outcome", "rejected").counter().count()).isEqualTo(1.0);
        assertThat(registry.get(GatewayAuthAttemptLimiter.ATTEMPT_METRIC)
                .tag("kind", "login").tag("outcome", "allowed").counter().count()).isEqualTo(4.0);
        assertThat(registry.get(GatewayAuthAttemptLimiter.SUCCESS_METRIC)
                .tag("kind", "login").counter().count()).isEqualTo(1.0);
    }

    @Test
    void crossingTheQuotaEmitsOneLimitEngagedSignal() {
        MeterRegistry registry = new SimpleMeterRegistry();
        GatewayAuthAttemptLimiter limiter = new GatewayAuthAttemptLimiter(
                mock(ReactiveStringRedisTemplate.class), registry, "memory", false,
                LOGIN_PERMITS, 1, 60);

        for (int attempt = 0; attempt < 6; attempt++) {
            limiter.acquire("login", "10.0.0.1", "alice").block();
        }

        assertThat(registry.get(GatewayAuthAttemptLimiter.SIGNAL_METRIC)
                .tag("signal", GatewayAuthAttemptLimiter.LIMIT_ENGAGED).counter().count())
                .isEqualTo(1.0);
    }

    @Test
    void oneSubjectFromManyAddressesIsReportedAsSharedAccountAbuse() {
        MeterRegistry registry = new SimpleMeterRegistry();
        GatewayAuthAttemptLimiter limiter = new GatewayAuthAttemptLimiter(
                mock(ReactiveStringRedisTemplate.class), registry, "memory", false,
                10, 10, 60, 3, 60);

        limiter.acquire("login", "10.0.0.1", "shared-analyst").block();
        limiter.acquire("login", "10.0.0.2", "shared-analyst").block();
        assertThat(registry.find(GatewayAuthAttemptLimiter.SIGNAL_METRIC)
                .tag("signal", GatewayAuthAttemptLimiter.SHARED_ACCOUNT).counter()).isNull();
        limiter.acquire("login", "10.0.0.3", "shared-analyst").block();

        assertThat(registry.get(GatewayAuthAttemptLimiter.SIGNAL_METRIC)
                .tag("signal", GatewayAuthAttemptLimiter.SHARED_ACCOUNT).counter().count())
                .isEqualTo(1.0);
        // The same client only retried once, so no spray may be inferred from it.
        assertThat(registry.find(GatewayAuthAttemptLimiter.SIGNAL_METRIC)
                .tag("signal", GatewayAuthAttemptLimiter.CREDENTIAL_SPRAY).counter()).isNull();
    }

    @Test
    void oneAddressTryingManySubjectsIsReportedAsCredentialSpray() {
        MeterRegistry registry = new SimpleMeterRegistry();
        GatewayAuthAttemptLimiter limiter = new GatewayAuthAttemptLimiter(
                mock(ReactiveStringRedisTemplate.class), registry, "memory", false,
                10, 10, 60, 3, 60);

        limiter.acquire("login", "203.0.113.9", "user-a").block();
        limiter.acquire("login", "203.0.113.9", "user-b").block();
        limiter.acquire("login", "203.0.113.9", "user-c").block();
        limiter.acquire("login", "203.0.113.9", "user-d").block();

        // The peer set is capped at the threshold, so the signal fires once per window
        // instead of on every additional guess.
        assertThat(registry.get(GatewayAuthAttemptLimiter.SIGNAL_METRIC)
                .tag("signal", GatewayAuthAttemptLimiter.CREDENTIAL_SPRAY).counter().count())
                .isEqualTo(1.0);
    }

    @Test
    void witnessLabelsStayWithinAFixedVocabulary() {
        MeterRegistry registry = new SimpleMeterRegistry();
        GatewayAuthAttemptLimiter limiter = new GatewayAuthAttemptLimiter(
                mock(ReactiveStringRedisTemplate.class), registry, "memory", false,
                1, 1, 60, 2, 1_000_000);

        // 200 distinct source addresses against five subjects: none of those strings may
        // become a Prometheus label value.
        for (int index = 0; index < 200; index++) {
            limiter.acquire("login", "198.51.100." + index, "victim-" + (index % 5)).block();
        }

        List<Tag> labels = registry.getMeters().stream()
                .filter(meter -> meter.getId().getName().startsWith("socp_auth_"))
                .flatMap(meter -> meter.getId().getTags().stream())
                .toList();
        assertThat(labels).isNotEmpty();
        assertThat(labels.stream().map(Tag::getKey).distinct().toList())
                .containsOnly("kind", "outcome", "signal");
        assertThat(labels.stream().map(Tag::getValue).distinct().toList())
                .allSatisfy(value -> assertThat(value)
                        .doesNotStartWith("victim")
                        .doesNotStartWith("198.51."));
        // Two attempt outcomes for one kind plus the fixed signal vocabulary: an attacker
        // choosing usernames and source addresses cannot grow the series count.
        assertThat(registry.find(GatewayAuthAttemptLimiter.ATTEMPT_METRIC).counters())
                .hasSizeLessThanOrEqualTo(2);
        assertThat(registry.find(GatewayAuthAttemptLimiter.SIGNAL_METRIC).counters())
                .hasSizeLessThanOrEqualTo(6);
        assertThat(registry.get(GatewayAuthAttemptLimiter.SIGNAL_METRIC)
                .tag("signal", GatewayAuthAttemptLimiter.SHARED_ACCOUNT).counter().count())
                .isEqualTo(5.0);
    }

    @Test
    void logBudgetKeepsCountingWithoutLogging() {
        MeterRegistry registry = new SimpleMeterRegistry();
        GatewayAuthAttemptLimiter limiter = new GatewayAuthAttemptLimiter(
                mock(ReactiveStringRedisTemplate.class), registry, "memory", false,
                100, 100, 3600, 3, 1);

        // Twenty subjects, each tried from three addresses: twenty shared-account edges,
        // one WARN line and a budget counter.
        for (int index = 0; index < 20; index++) {
            for (int client = 0; client < 3; client++) {
                limiter.acquire("login", "192.0.2." + client, "account-" + index).block();
            }
        }

        assertThat(registry.get(GatewayAuthAttemptLimiter.SIGNAL_METRIC)
                .tag("signal", GatewayAuthAttemptLimiter.SHARED_ACCOUNT).counter().count())
                .isEqualTo(20.0);
    }

    @Test
    void redisFailureRejectsWhenFailClosed() {
        ReactiveStringRedisTemplate redis = mock(ReactiveStringRedisTemplate.class);
        when(redis.execute(any(), anyList(), anyList())).thenReturn(Flux.error(new IllegalStateException("down")));
        MeterRegistry registry = new SimpleMeterRegistry();
        GatewayAuthAttemptLimiter limiter = new GatewayAuthAttemptLimiter(
                redis, registry, "redis", true, 5, 10, 60);

        AuthAttemptLimiter.Decision decision = limiter.acquire("service", "127.0.0.1", "alert-web").block();

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.retryAfterSeconds()).isEqualTo(1);
        assertThat(registry.get(GatewayAuthAttemptLimiter.SIGNAL_METRIC)
                .tag("signal", GatewayAuthAttemptLimiter.FAIL_CLOSED).counter().count())
                .isEqualTo(1.0);
    }

    @Test
    void redisFailureFallsBackToLocalWindowAndReportsIt() {
        ReactiveStringRedisTemplate redis = mock(ReactiveStringRedisTemplate.class);
        when(redis.execute(any(), anyList(), anyList())).thenReturn(Flux.error(new IllegalStateException("down")));
        MeterRegistry registry = new SimpleMeterRegistry();
        GatewayAuthAttemptLimiter limiter = new GatewayAuthAttemptLimiter(
                redis, registry, "redis", false, 1, 1, 60);

        assertThat(limiter.acquire("login", "127.0.0.1", "alice").block().allowed()).isTrue();
        assertThat(limiter.acquire("login", "127.0.0.1", "alice").block().allowed()).isFalse();

        assertThat(registry.get(GatewayAuthAttemptLimiter.SIGNAL_METRIC)
                .tag("signal", GatewayAuthAttemptLimiter.REDIS_FALLBACK).counter().count())
                .isEqualTo(2.0);
        assertThat(registry.get(GatewayAuthAttemptLimiter.SIGNAL_METRIC)
                .tag("signal", GatewayAuthAttemptLimiter.LIMIT_ENGAGED).counter().count())
                .isEqualTo(1.0);
    }

    @Test
    void redisDecisionIsUsedWithoutEvaluatingTheEmptyResponseFallback() {
        ReactiveStringRedisTemplate redis = mock(ReactiveStringRedisTemplate.class);
        when(redis.execute(any(), anyList(), anyList())).thenReturn(Flux.just(1L));
        GatewayAuthAttemptLimiter limiter = new GatewayAuthAttemptLimiter(
                redis, new SimpleMeterRegistry(), "redis", true, 5, 10, 60);

        AuthAttemptLimiter.Decision decision = limiter.acquire("login", "127.0.0.1", "alice").block();

        assertThat(decision.allowed()).isTrue();
    }

    @Test
    void deeperOverLimitRedisCountDoesNotRepeatTheEngagedSignal() {
        ReactiveStringRedisTemplate redis = mock(ReactiveStringRedisTemplate.class);
        MeterRegistry registry = new SimpleMeterRegistry();
        when(redis.execute(any(), anyList(), anyList())).thenReturn(Flux.just(8L));
        GatewayAuthAttemptLimiter limiter = new GatewayAuthAttemptLimiter(
                redis, registry, "redis", false, 5, 10, 60);

        AuthAttemptLimiter.Decision decision = limiter.acquire("login", "127.0.0.1", "alice").block();

        assertThat(decision.allowed()).isFalse();
        assertThat(registry.get(GatewayAuthAttemptLimiter.ATTEMPT_METRIC)
                .tag("kind", "login").tag("outcome", "rejected").counter().count()).isEqualTo(1.0);
        // Only the crossing count (permits + 1) is an edge; a deeper over-limit count is
        // the same ongoing condition and must not log again.
        assertThat(registry.find(GatewayAuthAttemptLimiter.SIGNAL_METRIC)
                .tag("signal", GatewayAuthAttemptLimiter.LIMIT_ENGAGED).counter()).isNull();
    }

}