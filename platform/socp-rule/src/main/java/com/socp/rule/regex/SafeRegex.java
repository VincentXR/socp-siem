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
            String[] required = requiredAlternatives(expression);
            return input -> {
                String text = input == null ? "" : input;
                if (required != null && ascii(text)) {
                    String lower = text.toLowerCase(java.util.Locale.ROOT);
                    boolean possible = false;
                    for (String literal : required) {
                        if (lower.contains(literal)) { possible = true; break; }
                    }
                    if (!possible) return false;
                }
                return pattern.matcher(text).find();
            };
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("invalid or unsupported regex", ex);
        }
    }

    private static boolean ascii(String text) {
        for (int i = 0; i < text.length(); i++) if (text.charAt(i) > 127) return false;
        return true;
    }

    /**
     * Conservative necessary literals, never a replacement matcher. Each top-level
     * branch must start with a non-optional literal or a group of literal choices,
     * optionally after ^ or .*. Unsupported syntax keeps the original RE2 path.
     */
    private static String[] requiredAlternatives(String expression) {
        String text = expression.startsWith("(?i)") ? expression.substring(4) : expression;
        if (!ascii(text) || text.indexOf('?') >= 0 || text.indexOf('[') >= 0
                || text.indexOf('{') >= 0 || text.contains("\\Q")) return null;
        while (text.startsWith("(") && groupEnd(text, 0) == text.length() - 1) {
            text = text.substring(1, text.length() - 1);
        }
        var branches = new java.util.ArrayList<String>();
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\\') i++;
            else if (c == '(') {
                i = groupEnd(text, i);
                if (i < 0) return null;
            } else if (c == '|') {
                branches.add(text.substring(start, i));
                start = i + 1;
            }
        }
        branches.add(text.substring(start));
        var required = new java.util.LinkedHashSet<String>();
        for (String branch : branches) {
            int i = branch.startsWith("^") ? 1 : 0;
            if (branch.startsWith(".*", i)) i += 2;
            if (i >= branch.length()) return null;
            String[] choices;
            if (branch.charAt(i) == '(') {
                int end = groupEnd(branch, i);
                if (end < 0) return null;
                choices = literalAlternatives(branch.substring(i + 1, end));
                i = end + 1;
            } else {
                StringBuilder literal = new StringBuilder();
                for (; i < branch.length(); i++) {
                    char c = branch.charAt(i);
                    if (c == '\\') {
                        if (i + 1 >= branch.length() || "\\.*+?()[]{}^$|".indexOf(branch.charAt(i + 1)) < 0) break;
                        literal.append(branch.charAt(++i));
                    } else {
                        if (".*+?()[]{}^$|".indexOf(c) >= 0) break;
                        literal.append(c);
                    }
                }
                choices = literal.isEmpty() ? null
                        : new String[]{literal.toString().toLowerCase(java.util.Locale.ROOT)};
            }
            // A quantifier can make the last literal/group optional. Even + is
            // left to RE2 so this recognizer never interprets repetition syntax.
            if (choices == null || (i < branch.length() && "*+?{".indexOf(branch.charAt(i)) >= 0)) return null;
            java.util.Collections.addAll(required, choices);
        }
        return required.toArray(String[]::new);
    }

    /** Locate a plain group's boundary; character classes/quoted regions are excluded above. */
    private static int groupEnd(String text, int start) {
        int depth = 0;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\\') i++;
            else if (c == '(') depth++;
            else if (c == ')' && --depth == 0) return i;
        }
        return -1;
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
