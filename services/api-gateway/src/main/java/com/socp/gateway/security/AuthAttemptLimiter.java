package com.socp.gateway.security;

import reactor.core.publisher.Mono;

/** Brute-force guard for public authentication endpoints. */
public interface AuthAttemptLimiter {

    Mono<Decision> acquire(String kind, String clientAddress, String identity);

    Mono<Void> reset(String kind, String clientAddress, String identity);

    record Decision(boolean allowed, long retryAfterSeconds) {
        public static Decision permit() {
            return new Decision(true, 0);
        }

        public static Decision reject(long retryAfterSeconds) {
            return new Decision(false, Math.max(1, retryAfterSeconds));
        }
    }
}
