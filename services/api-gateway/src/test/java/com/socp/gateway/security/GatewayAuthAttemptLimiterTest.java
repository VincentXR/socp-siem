package com.socp.gateway.security;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import reactor.core.publisher.Flux;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GatewayAuthAttemptLimiterTest {

    @Test
    void localBackendLimitsByAddressAndIdentityAndCanReset() {
        GatewayAuthAttemptLimiter limiter = new GatewayAuthAttemptLimiter(
                mock(ReactiveStringRedisTemplate.class), "memory", false, 2, 1, 60);

        assertThat(limiter.acquire("login", "127.0.0.1", "alice").block().allowed()).isTrue();
        assertThat(limiter.acquire("login", "127.0.0.1", "alice").block().allowed()).isTrue();
        assertThat(limiter.acquire("login", "127.0.0.1", "alice").block().allowed()).isFalse();
        assertThat(limiter.acquire("login", "127.0.0.1", "bob").block().allowed()).isTrue();

        limiter.reset("login", "127.0.0.1", "alice").block();
        assertThat(limiter.acquire("login", "127.0.0.1", "alice").block().allowed()).isTrue();
    }

    @Test
    void redisFailureRejectsWhenFailClosed() {
        ReactiveStringRedisTemplate redis = mock(ReactiveStringRedisTemplate.class);
        when(redis.execute(any(), anyList(), anyList())).thenReturn(Flux.error(new IllegalStateException("down")));
        GatewayAuthAttemptLimiter limiter = new GatewayAuthAttemptLimiter(
                redis, "redis", true, 5, 10, 60);

        AuthAttemptLimiter.Decision decision = limiter.acquire("service", "127.0.0.1", "alert-web").block();

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.retryAfterSeconds()).isEqualTo(1);
    }
}
