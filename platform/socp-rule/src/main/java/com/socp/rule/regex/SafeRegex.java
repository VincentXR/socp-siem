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
            return input -> pattern.matcher(input == null ? "" : input).find();
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("invalid or unsupported regex", ex);
        }
    }

    public static void validateCaseInsensitive(String expression) {
        compileCaseInsensitive(expression);
    }

    @FunctionalInterface
    public interface Compiled {
        boolean find(String input);
    }
}
