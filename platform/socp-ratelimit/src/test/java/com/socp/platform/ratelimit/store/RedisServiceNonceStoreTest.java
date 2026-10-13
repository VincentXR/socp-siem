package com.socp.platform.ratelimit.store;

import com.socp.platform.auth.security.ServiceNonceStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(OutputCaptureExtension.class)
class RedisServiceNonceStoreTest {

    @Test
    void reportsRootFailureWithoutLoggingRedisCredentialsOrNonce(CapturedOutput output) {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.opsForValue()).thenThrow(new IllegalStateException(
                "connection credential=private-credential", new OutOfMemoryError("Metaspace")));

        assertThat(new RedisServiceNonceStore(redis).claim("alert-web", "private-nonce", Duration.ofSeconds(60)))
                .isEqualTo(ServiceNonceStore.ClaimResult.UNAVAILABLE);
        assertThat(output.getAll()).contains("rootCause=java.lang.OutOfMemoryError", "memoryArea=Metaspace")
                .doesNotContain("private-credential", "private-nonce");
    }

    @ParameterizedTest
    @CsvSource({"Java heap space,Java heap space", "Direct buffer memory,Direct buffer memory",
            "Compressed class space,Compressed class space", "private-value,unspecified", ",unspecified"})
    void limitsMemoryDiagnosticsToKnownSafeLabels(String message, String expected, CapturedOutput output) {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.opsForValue()).thenThrow(new IllegalStateException(new OutOfMemoryError(message)));
        assertThat(new RedisServiceNonceStore(redis).claim("alert-web", "private-nonce", Duration.ofSeconds(60)))
                .isEqualTo(ServiceNonceStore.ClaimResult.UNAVAILABLE);
        assertThat(output.getAll()).contains("memoryArea=" + expected)
                .doesNotContain("private-value", "private-nonce");
    }

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
