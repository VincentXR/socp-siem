package com.socp.platform.ratelimit.model;
/**
 * 简单内存令牌桶。本地切片用；多实例需换成 Redisson 分布式令牌桶（见架构 §3 / P1）。
 *
 * <p>语义：容量 = permits，在 seconds 秒内匀速补满 permits 个令牌，
 * 即每 {@code seconds/permits} 秒生成 1 个令牌（而非每 seconds 秒生成 1 个）。
 * 补充时保留时间余数，避免高频调用下的令牌漂移丢失。
 *
 * <p>所有状态变更都在 synchronized 内，故用普通 long 即可，无需原子类。
 */
public class TokenBucket {
    private final long capacity;
    /** 生成 1 个令牌所需纳秒 */
    private final long nanosPerToken;
    private long tokens;
    private long lastRefill;

    public TokenBucket(int permits, int seconds) {
        int p = Math.max(1, permits);
        int s = Math.max(1, seconds);
        this.capacity = p;
        this.tokens = p;
        this.nanosPerToken = Math.max(1L, (long) s * 1_000_000_000L / p);
        this.lastRefill = System.nanoTime();
    }

    public synchronized boolean tryAcquire() {
        refill();
        if (tokens > 0) {
            tokens--;
            return true;
        }
        return false;
    }

    /** 距离下一个令牌可用还需几秒（向上取整，最小 1），用于回写 Retry-After 响应头。 */
    public synchronized long retryAfterSeconds() {
        long wait = nanosPerToken - (System.nanoTime() - lastRefill);
        if (wait <= 0) {
            return 1L;
        }
        return Math.max(1L, (wait + 999_999_999L) / 1_000_000_000L);
    }

    private void refill() {
        long now = System.nanoTime();
        long elapsed = now - lastRefill;
        if (elapsed < nanosPerToken) {
            return;
        }
        long add = elapsed / nanosPerToken;
        tokens = Math.min(capacity, tokens + add);
        // 只推进已消费掉的整数倍时间，保留余数
        lastRefill += add * nanosPerToken;
    }
}
