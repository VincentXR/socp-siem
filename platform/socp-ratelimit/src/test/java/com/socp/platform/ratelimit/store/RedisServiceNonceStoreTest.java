package com.socp.platform.ratelimit.store;

import com.socp.platform.auth.security.ServiceNonceStore;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RedisServiceNonceStoreTest {

    @Test
    void mapsAtomicSetIfAbsentToClaimResultsAndFailsClosed() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        RedisServiceNonceStore store = new RedisServiceNonceStore(redis);

        when(values.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        assertThat(store.claim("alert-web", "n-1", Duration.ofSeconds(60)))
                .isEqualTo(ServiceNonceStore.ClaimResult.CLAIMED);

        when(values.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(false);
        assertThat(store.claim("alert-web", "n-1", Duration.ofSeconds(60)))
                .isEqualTo(ServiceNonceStore.ClaimResult.REPLAYED);

        when(values.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenThrow(new IllegalStateException("redis down"));
        assertThat(store.claim("alert-web", "n-2", Duration.ofSeconds(60)))
                .isEqualTo(ServiceNonceStore.ClaimResult.UNAVAILABLE);
    }
}
