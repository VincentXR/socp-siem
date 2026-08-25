package com.socp.gateway.oidc;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;

/** Redis-backed, cross-gateway PKCE state with atomic consume semantics. */
@Component
@ConditionalOnProperty(name = "socp.oidc.state.backend", havingValue = "redis", matchIfMissing = true)
class RedisOidcStateStore implements OidcStateStore {

    private static final DefaultRedisScript<String> CONSUME = new DefaultRedisScript<>("""
            local value = redis.call('GET', KEYS[1])
            if value then redis.call('DEL', KEYS[1]) end
            return value
            """, String.class);

    private final ReactiveStringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    @Value("${socp.oidc.state.redis-key-prefix:socp:oidc:state:}")
    private String keyPrefix;

    RedisOidcStateStore(ReactiveStringRedisTemplate redis, ObjectMapper objectMapper) {
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    @Override
    public Mono<Void> save(String state, Entry entry, Duration ttl) {
        return redis.opsForValue().set(key(state), serialize(entry), ttl).then();
    }

    @Override
    public Mono<Entry> consume(String state) {
        if (!validState(state)) return Mono.empty();
        return redis.execute(CONSUME, List.of(key(state)))
                .next()
                .flatMap(value -> value == null || value.isBlank() ? Mono.empty() : Mono.just(deserialize(value)));
    }

    private String key(String state) {
        if (!validState(state)) throw new IllegalArgumentException("OIDC state is malformed");
        return keyPrefix + state;
    }

    private static boolean validState(String state) {
        return state != null && state.length() >= 32 && state.length() <= 512
                && state.chars().allMatch(c -> Character.isLetterOrDigit(c) || c == '-' || c == '_');
    }

    private String serialize(Entry entry) {
        try {
            return objectMapper.writeValueAsString(entry);
        } catch (Exception failure) {
            throw new IllegalStateException("Unable to serialize OIDC state", failure);
        }
    }

    private Entry deserialize(String value) {
        try {
            return objectMapper.readValue(value, Entry.class);
        } catch (Exception failure) {
            throw new IllegalStateException("Unable to read OIDC state", failure);
        }
    }
}
