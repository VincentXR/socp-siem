package com.socp.rule.time;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Event-time ordering contract for stateful rules.
 *
 * <p>The watermark is the greatest event timestamp observed for one rule
 * grouping key.  A record older than {@code watermark - allowedLateness} is
 * late.  {@link LateEventHandling#DROP} makes that decision deterministic and
 * prevents an old replay from mutating a current window; {@link
 * LateEventHandling#ACCEPT} keeps the existing bounded-window behavior while
 * still leaving the watermark monotonic.</p>
 */
public record EventTimePolicy(Duration allowedLateness, LateEventHandling handling) {

    public EventTimePolicy {
        if (allowedLateness == null || allowedLateness.isNegative()) {
            throw new IllegalArgumentException("allowedLateness must be non-negative");
        }
        if (handling == null) throw new IllegalArgumentException("late event handling is required");
    }

    /** Default policy: allow one rule window of reordering, then drop records. */
    public static EventTimePolicy defaultFor(Duration window) {
        Duration fallback = window == null || window.isNegative() ? Duration.ZERO : window;
        return new EventTimePolicy(fallback, LateEventHandling.DROP);
    }

    /** Parse the persisted DSL object; absent policy uses the safe window default. */
    public static EventTimePolicy parse(Object raw, Duration window) {
        EventTimePolicy fallback = defaultFor(window);
        if (raw == null) return fallback;

        Object allowed = raw;
        Object mode = null;
        if (raw instanceof Map<?, ?> values) {
            allowed = values.containsKey("allowedLateness")
                    ? values.get("allowedLateness") : values.get("maxOutOfOrder");
            mode = values.containsKey("handling") ? values.get("handling") : values.get("lateEventHandling");
        }
        Duration allowedLateness = allowed == null || String.valueOf(allowed).isBlank()
                ? fallback.allowedLateness() : parseDuration(String.valueOf(allowed));
        LateEventHandling handling = mode == null || String.valueOf(mode).isBlank()
                ? fallback.handling() : LateEventHandling.valueOf(String.valueOf(mode).trim().toUpperCase());
        return new EventTimePolicy(allowedLateness, handling);
    }

    public boolean isLate(Instant timestamp, Instant watermark) {
        return timestamp != null && watermark != null
                && timestamp.isBefore(watermark.minus(allowedLateness));
    }

    public Map<String, Object> toMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("allowedLateness", formatDuration(allowedLateness));
        out.put("handling", handling.name());
        return out;
    }

    private static Duration parseDuration(String value) {
        String text = value.trim();
        try {
            if (text.startsWith("P")) return Duration.parse(text);
            if (text.endsWith("ms")) return Duration.ofMillis(Long.parseLong(text.substring(0, text.length() - 2)));
            if (text.endsWith("s")) return Duration.ofSeconds(Long.parseLong(text.substring(0, text.length() - 1)));
            if (text.endsWith("m")) return Duration.ofMinutes(Long.parseLong(text.substring(0, text.length() - 1)));
            if (text.endsWith("h")) return Duration.ofHours(Long.parseLong(text.substring(0, text.length() - 1)));
            if (text.endsWith("d")) return Duration.ofDays(Long.parseLong(text.substring(0, text.length() - 1)));
            return Duration.ofSeconds(Long.parseLong(text));
        } catch (RuntimeException failure) {
            throw new IllegalArgumentException("invalid allowedLateness: " + value, failure);
        }
    }

    private static String formatDuration(Duration duration) {
        long seconds = duration.getSeconds();
        if (duration.getNano() == 0) {
            if (seconds == 0) return "0s";
            if (seconds % 86_400 == 0) return (seconds / 86_400) + "d";
            if (seconds % 3_600 == 0) return (seconds / 3_600) + "h";
            if (seconds % 60 == 0) return (seconds / 60) + "m";
            return seconds + "s";
        }
        return duration.toString();
    }

    public enum LateEventHandling {
        DROP,
        ACCEPT
    }
}
