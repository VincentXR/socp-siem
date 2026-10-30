package com.socp.rule.regex;

import com.google.re2j.Pattern;

/**
 * Detection-rule regex boundary.
 *
 * <p>Rules are authored by users and evaluated on every matching event, so a
 * backtracking engine is not an acceptable execution primitive. This wrapper
 * keeps validation and runtime compilation on the same RE2/J subset and adds
 * explicit size budgets before a regex reaches the hot path.</p>
 */
public final class SafeRegex {

    public static final int MAX_PATTERN_CHARS = 2_048;
    public static final int MAX_PROGRAM_SIZE = 4_096;

    private SafeRegex() {
    }

    public static Compiled compileCaseInsensitive(String expression) {
        if (expression == null) {
            throw new IllegalArgumentException("regex is required");
        }
        if (expression.length() > MAX_PATTERN_CHARS) {
            throw new IllegalArgumentException(
                    "regex exceeds " + MAX_PATTERN_CHARS + " characters");
        }
        try {
            Pattern pattern = Pattern.compile(expression, Pattern.CASE_INSENSITIVE);
            if (pattern.programSize() > MAX_PROGRAM_SIZE) {
                throw new IllegalArgumentException(
                        "regex program exceeds " + MAX_PROGRAM_SIZE + " instructions");
            }
            String[] literals = literalAlternatives(expression);
            if (literals != null) {
                return input -> {
                    String text = input == null ? "" : input;
                    // RE2 Unicode folding includes non-ASCII equivalents (for example Kelvin K).
                    // Keep that exact behavior by using RE2 whenever the input is not ASCII.
                    for (int i = 0; i < text.length(); i++) {
                        if (text.charAt(i) > 127) return pattern.matcher(text).find();
                    }
                    String lower = text.toLowerCase(java.util.Locale.ROOT);
                    for (String literal : literals) if (lower.contains(literal)) return true;
                    return false;
                };
            }
            return input -> pattern.matcher(input == null ? "" : input).find();
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("invalid or unsupported regex", ex);
        }
    }

    /** Recognize only complete ASCII literal alternatives; everything else stays on RE2. */
    private static String[] literalAlternatives(String expression) {
        String text = expression.startsWith("(?i)") ? expression.substring(4) : expression;
        if (text.startsWith("(") && text.endsWith(")")) text = text.substring(1, text.length() - 1);
        java.util.List<String> values = new java.util.ArrayList<>();
        StringBuilder literal = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c > 127) return null;
            if (c == '|') {
                if (literal.isEmpty()) return null;
                values.add(literal.toString().toLowerCase(java.util.Locale.ROOT));
                literal.setLength(0);
            } else if (c == '\\') {
                if (++i >= text.length()) return null;
                char escaped = text.charAt(i);
                if ("\\.*+?()[]{}^$|".indexOf(escaped) < 0) return null;
                literal.append(escaped);
            } else {
                if (".*+?()[]{}^$".indexOf(c) >= 0) return null;
                literal.append(c);
            }
        }
        if (literal.isEmpty()) return null;
        values.add(literal.toString().toLowerCase(java.util.Locale.ROOT));
        return values.toArray(String[]::new);
    }

    public static void validateCaseInsensitive(String expression) {
        compileCaseInsensitive(expression);
    }

    @FunctionalInterface
    public interface Compiled {
        boolean find(String input);
    }
}
