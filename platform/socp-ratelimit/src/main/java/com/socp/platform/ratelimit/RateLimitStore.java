package com.socp.platform.ratelimit;

public interface RateLimitStore {
    Decision acquire(String key, int permits, int seconds);

    record Decision(boolean allowed, long retryAfterSeconds) {
        static Decision permit() { return new Decision(true, 0); }
        static Decision rejected(long retryAfterSeconds) {
            return new Decision(false, Math.max(1, retryAfterSeconds));
        }
    }
}
