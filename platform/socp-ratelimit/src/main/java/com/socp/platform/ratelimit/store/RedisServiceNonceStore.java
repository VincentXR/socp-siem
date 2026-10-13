package com.socp.platform.ratelimit.store;

import com.socp.platform.auth.security.ServiceNonceStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** Redis SET NX replay guard shared by every service instance. */
@Component
@ConditionalOnProperty(name = "socp.ratelimit.backend", havingValue = "redis")
public class RedisServiceNonceStore implements ServiceNonceStore {

    private static final Logger log = LoggerFactory.getLogger(RedisServiceNonceStore.class);
    private static final String PREFIX = "socp:auth:service-nonce:";

    private final StringRedisTemplate redis;

    public RedisServiceNonceStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public ClaimResult claim(String service, String nonce, Duration ttl) {
        try {
            Boolean claimed = redis.opsForValue().setIfAbsent(
                    PREFIX + service + ':' + nonce, "1", ttl.isZero() ? Duration.ofSeconds(1) : ttl);
            return Boolean.TRUE.equals(claimed) ? ClaimResult.CLAIMED : ClaimResult.REPLAYED;
        } catch (RuntimeException unavailable) {
            Throwable root = NestedExceptionUtils.getMostSpecificCause(unavailable);
            // Redis wrappers can hide local VM failures behind "Unknown redis exception".
            // Do not log arbitrary exception text, which can include connection credentials.
            String memoryArea = root instanceof OutOfMemoryError ? switch (String.valueOf(root.getMessage())) {
                case "Metaspace", "Compressed class space", "Java heap space", "Direct buffer memory" -> root.getMessage();
                default -> "unspecified";
            } : "not-applicable";
            log.error("Redis service nonce guard unavailable; rejecting signed request: type={} rootCause={} memoryArea={}",
                    unavailable.getClass().getName(), root.getClass().getName(), memoryArea);
            return ClaimResult.UNAVAILABLE;
        }
    }
}
