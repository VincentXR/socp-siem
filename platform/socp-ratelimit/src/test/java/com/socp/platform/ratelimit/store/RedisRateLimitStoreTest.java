package com.socp.platform.ratelimit.store;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RedisRateLimitStoreTest {

    @Test
    void redisFailureFailsClosedWhenConfigured() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.execute(any(), anyList(), anyString())).thenThrow(new IllegalStateException("redis down"));

        RateLimitStore.Decision decision = new RedisRateLimitStore(redis, true)
                .acquire("tenant-a|login", 10, 1);

        assertFalse(decision.allowed());
    }

    @Test
    void usableRedisDecisionIsReturned() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.execute(any(), anyList(), anyString())).thenReturn(List.of(1L, 1L));

        RateLimitStore.Decision decision = new RedisRateLimitStore(redis, true)
                .acquire("tenant-a|login", 10, 1);

        assertTrue(decision.allowed());
    }
}
