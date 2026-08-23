package com.socp.rule.state;

import java.time.Duration;

/** Runtime limits shared by all stateful detection rules. */
public record RuleStateLimits(int maxKeys, Duration idleTtl) {

    public RuleStateLimits {
        if (maxKeys < 1) throw new IllegalArgumentException("maxKeys must be positive");
        if (idleTtl == null || idleTtl.isNegative() || idleTtl.isZero()) {
            throw new IllegalArgumentException("idleTtl must be positive");
        }
    }

    public static RuleStateLimits defaults() {
        int max = integerSetting("socp.rule.state.max-keys", "SOCP_RULE_STATE_MAX_KEYS", 100_000);
        long ttlMs = longSetting("socp.rule.state.idle-ttl-ms", "SOCP_RULE_STATE_IDLE_TTL_MS",
                Duration.ofMinutes(30).toMillis());
        return new RuleStateLimits(max, Duration.ofMillis(ttlMs));
    }

    private static int integerSetting(String property, String env, int fallback) {
        String value = System.getProperty(property, System.getenv(env));
        try {
            return value == null ? fallback : Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static long longSetting(String property, String env, long fallback) {
        String value = System.getProperty(property, System.getenv(env));
        try {
            return value == null ? fallback : Long.parseLong(value);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }
}
