package com.socp.gateway.security;

import com.socp.platform.test.MiddlewareImages;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/** Real Redis proof that revocation is visible to another gateway instance. */
@Testcontainers
@EnabledIfEnvironmentVariable(named = "SOCP_TESTCONTAINERS", matches = "true")
class RedisTokenRevocationStoreContainerTest {

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(MiddlewareImages.redis())
            .withExposedPorts(6379);

    private static LettuceConnectionFactory firstFactory;
    private static LettuceConnectionFactory secondFactory;
    private static RedisTokenRevocationStore first;
    private static RedisTokenRevocationStore second;

    @BeforeAll
    static void connectBothGatewayStores() {
        firstFactory = factory();
        secondFactory = factory();
        first = store(firstFactory);
        second = store(secondFactory);
    }

    @AfterAll
    static void closeFactories() {
        if (firstFactory != null) firstFactory.destroy();
        if (secondFactory != null) secondFactory.destroy();
    }

    @Test
    void revocationWrittenByOneReplicaIsReadByAnother() {
        String jti = "container-jti-" + System.nanoTime();

        first.revoke(jti, Instant.now().plusSeconds(60)).block();

        assertThat(second.isRevoked(jti).block()).isTrue();
    }

    private static LettuceConnectionFactory factory() {
        RedisStandaloneConfiguration configuration = new RedisStandaloneConfiguration(
                REDIS.getHost(), REDIS.getMappedPort(6379));
        LettuceConnectionFactory factory = new LettuceConnectionFactory(configuration);
        factory.afterPropertiesSet();
        return factory;
    }

    private static RedisTokenRevocationStore store(LettuceConnectionFactory factory) {
        RedisTokenRevocationStore store = new RedisTokenRevocationStore(
                new ReactiveStringRedisTemplate(factory));
        ReflectionTestUtils.setField(store, "keyPrefix", "socp:test:revoked:");
        return store;
    }
}
