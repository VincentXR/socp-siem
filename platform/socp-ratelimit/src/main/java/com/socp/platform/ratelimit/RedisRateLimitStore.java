package com.socp.platform.ratelimit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@ConditionalOnProperty(name = "socp.ratelimit.backend", havingValue = "redis")
public class RedisRateLimitStore implements RateLimitStore {

    private static final Logger log = LoggerFactory.getLogger(RedisRateLimitStore.class);
    private static final DefaultRedisScript<List> ACQUIRE = new DefaultRedisScript<>("""
            local current = redis.call('INCR', KEYS[1])
            if current == 1 then redis.call('EXPIRE', KEYS[1], ARGV[1]) end
            local ttl = redis.call('TTL', KEYS[1])
            return {current, ttl}
            """, List.class);

    private final StringRedisTemplate redis;
    private final InMemoryRateLimitStore fallback = new InMemoryRateLimitStore();

    public RedisRateLimitStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public Decision acquire(String key, int permits, int seconds) {
        try {
            List<?> result = redis.execute(ACQUIRE, List.of(key), String.valueOf(Math.max(1, seconds)));
            if (result != null && result.size() >= 2) {
                long count = ((Number) result.get(0)).longValue();
                long ttl = ((Number) result.get(1)).longValue();
                return count <= Math.max(1, permits) ? Decision.permit() : Decision.rejected(ttl);
            }
        } catch (Exception ex) {
            log.warn("Redis rate limit check failed, falling back to in-memory bucket: {}", ex.getMessage());
            return fallback.acquire(key, permits, seconds);
        }
        return fallback.acquire(key, permits, seconds);
    }
}
