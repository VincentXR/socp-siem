package com.socp.gateway.security;

import com.socp.gateway.oidc.InMemoryOidcStateStore;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.context.annotation.Profile;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Locks the module standard for process-local session backends: the memory
 * implementations are development conveniences and must never be selectable in
 * the prod profile, where a second gateway replica makes them wrong.
 */
class MemoryBackendProfileTest {

    @Test
    void memorySessionBackendsAreExcludedFromProd() {
        for (Class<?> backend : new Class<?>[]{InMemoryTokenRevocationStore.class, InMemoryOidcStateStore.class}) {
            ConditionalOnProperty property =
                    AnnotatedElementUtils.findMergedAnnotation(backend, ConditionalOnProperty.class);
            assertThat(property).as(backend.getSimpleName() + " backend switch").isNotNull();
            assertThat(property.havingValue()).isEqualTo("memory");
            Profile profile = AnnotatedElementUtils.findMergedAnnotation(backend, Profile.class);
            assertThat(profile).as(backend.getSimpleName() + " profile gate").isNotNull();
            assertThat(profile.value()).containsExactly("!prod");
        }
    }

    @Test
    void redisBackendsRemainTheDefault() {
        assertThat(AnnotatedElementUtils.findMergedAnnotation(RedisTokenRevocationStore.class,
                ConditionalOnProperty.class).matchIfMissing()).isTrue();
    }
}
