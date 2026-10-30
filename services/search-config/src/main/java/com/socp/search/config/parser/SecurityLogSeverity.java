package com.socp.search.config.parser;

import java.util.Locale;

/** CEF/LEEF severity increases with the numeric value; Very-High maps to CRITICAL. */
final class SecurityLogSeverity {
    private SecurityLogSeverity() { }

    static String normalize(String value) {
        String text = value == null ? "" : value.trim();
        try {
            int severity = Integer.parseInt(text);
            if (severity < 0 || severity > 10) throw new IllegalArgumentException("severity outside 0..10");
            if (severity <= 3) return "LOW";
            if (severity <= 6) return "MEDIUM";
            if (severity <= 8) return "HIGH";
            return "CRITICAL";
        } catch (NumberFormatException notNumeric) {
            return switch (text.toUpperCase(Locale.ROOT)) {
                case "", "UNKNOWN" -> "INFO";
                case "VERY-HIGH" -> "CRITICAL";
                default -> text;
            };
        }
    }
}
