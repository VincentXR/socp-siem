package com.socp.soar.web.definition;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Small, deterministic CEL-compatible subset used at the JSON boundary. It
 * intentionally supports data predicates only; reflection, method calls,
 * time/randomness, networking and script evaluation are not part of the
 * grammar. The validator rejects anything outside this subset before publish.
 */
public final class SoarExpressionEngine {
    private SoarExpressionEngine() { }

    public static boolean isSafe(String expression) {
        if (expression == null || expression.isBlank()) return true;
        return expression.length() <= 4096
                && expression.matches("[A-Za-z0-9_ .\\\"'()<>!=&|+\\-*/%,\\[\\]]+")
                && !expression.contains("__")
                && !expression.toLowerCase(java.util.Locale.ROOT).matches(".*(java\\.|class|reflect|runtime|exec|new ).*")
                && wellFormed(expression);
    }

    public static boolean evaluate(String expression, Map<String, ?> context) {
        if (!isSafe(expression)) throw new IllegalArgumentException("unsafe expression");
        String value = expression == null ? "" : expression.trim();
        if (value.isBlank() || "true".equalsIgnoreCase(value)) return true;
        if ("false".equalsIgnoreCase(value)) return false;
        if (value.startsWith("!") && !value.startsWith("!=") && value.length() > 1) {
            return !evaluate(value.substring(1), context);
        }
        if (value.startsWith("(") && value.endsWith(")") && enclosesWholeExpression(value)) {
            return evaluate(value.substring(1, value.length() - 1), context);
        }
        int split = topLevel(value, "||");
        if (split >= 0) return evaluate(value.substring(0, split), context)
                || evaluate(value.substring(split + 2), context);
        split = topLevel(value, "&&");
        if (split >= 0) return evaluate(value.substring(0, split), context)
                && evaluate(value.substring(split + 2), context);
        split = topLevel(value, " in ");
        if (split >= 0) {
            Object left = resolve(value.substring(0, split), context);
            String collection = value.substring(split + 4).trim();
            if (!collection.startsWith("[") || !collection.endsWith("]")) return false;
            for (String item : splitList(collection.substring(1, collection.length() - 1))) {
                if (compare(left, resolve(item, context)) == 0) return true;
            }
            return false;
        }
        String lower = value.toLowerCase(java.util.Locale.ROOT);
        if (lower.endsWith(" exists()")) return lookup(value.substring(0, value.length() - 9), context) != null;
        split = comparison(value);
        if (split < 0) return Boolean.parseBoolean(String.valueOf(resolve(value, context)));
        String[] op = operatorAt(value, split);
        Object left = resolve(value.substring(0, split), context);
        Object right = resolve(value.substring(split + op[0].length()), context);
        if ("contains".equals(op[0])) return String.valueOf(left).contains(String.valueOf(right));
        int compare = compare(left, right);
        return switch (op[0]) {
            case "==" -> compare == 0;
            case "!=" -> compare != 0;
            case ">" -> compare > 0;
            case "<" -> compare < 0;
            case ">=" -> compare >= 0;
            case "<=" -> compare <= 0;
            default -> false;
        };
    }

    private static int comparison(String value) {
        for (String operator : List.of("contains", "==", "!=", ">=", "<=", ">", "<")) {
            int at = topLevel(value, operator);
            if (at >= 0) return at;
        }
        return -1;
    }

    private static String[] operatorAt(String value, int at) {
        for (String operator : List.of("contains", "==", "!=", ">=", "<=", ">", "<"))
            if (value.regionMatches(at, operator, 0, operator.length())) return new String[]{operator};
        return new String[]{"=="};
    }

    private static Object resolve(String text, Map<String, ?> context) {
        String value = text == null ? "" : text.trim();
        if ((value.startsWith("\"") && value.endsWith("\"")) || (value.startsWith("'") && value.endsWith("'")))
            return value.substring(1, value.length() - 1);
        if ("null".equalsIgnoreCase(value)) return null;
        if ("true".equalsIgnoreCase(value) || "false".equalsIgnoreCase(value)) return Boolean.parseBoolean(value);
        if (value.startsWith("[") && value.endsWith("]")) {
            List<Object> values = new ArrayList<>();
            for (String item : splitList(value.substring(1, value.length() - 1))) values.add(resolve(item, context));
            return values;
        }
        try { if (value.matches("-?[0-9]+")) return Long.parseLong(value); }
        catch (NumberFormatException ignored) { }
        return lookup(value, context);
    }

    private static Object lookup(String path, Map<String, ?> context) {
        if (context == null) context = Map.of();
        String normalized = path.trim();
        if (context.containsKey(normalized)) return context.get(normalized);
        normalized = normalized.replaceFirst("^(vars|variables)\\.", "");
        if (context.containsKey(normalized)) return context.get(normalized);
        // Workflow projections may intentionally use flattened keys such as
        // nodes.lookup.output. Resolve the longest matching prefix before
        // falling back to a conventional nested object traversal.
        String[] parts = normalized.split("\\.");
        for (int prefixLength = parts.length - 1; prefixLength > 0; prefixLength--) {
            String prefix = String.join(".", java.util.Arrays.copyOf(parts, prefixLength));
            if (!context.containsKey(prefix)) continue;
            Object current = context.get(prefix);
            for (int index = prefixLength; index < parts.length; index++) {
                if (!(current instanceof Map<?, ?> map)) return null;
                current = map.get(parts[index]);
            }
            return current;
        }
        Object current = context;
        for (String part : parts) {
            if (!(current instanceof Map<?, ?> map)) return null;
            current = map.get(part);
        }
        return current;
    }

    private static int compare(Object left, Object right) {
        if (left == right) return 0;
        if (left == null) return -1;
        if (right == null) return 1;
        if (left instanceof Number l && right instanceof Number r) return Double.compare(l.doubleValue(), r.doubleValue());
        return String.valueOf(left).compareToIgnoreCase(String.valueOf(right));
    }

    /** Reject syntax the evaluator cannot interpret instead of silently
     * converting it to boolean false (publish-time fail closed). */
    private static boolean wellFormed(String expression) {
        int parentheses = 0;
        int brackets = 0;
        int nesting = 0;
        boolean quoted = false;
        char quote = 0;
        for (int i = 0; i < expression.length(); i++) {
            char ch = expression.charAt(i);
            if ((ch == '\'' || ch == '"') && (i == 0 || expression.charAt(i - 1) != '\\')) {
                if (quoted && ch == quote) quoted = false;
                else if (!quoted) { quoted = true; quote = ch; }
                continue;
            }
            if (quoted) continue;
            if (ch == '(') { parentheses++; nesting++; }
            else if (ch == ')') { if (--parentheses < 0) return false; nesting--; }
            else if (ch == '[') { brackets++; nesting++; }
            else if (ch == ']') { if (--brackets < 0) return false; nesting--; }
            if (nesting > 20) return false;
            // Arithmetic is not part of this deliberately small evaluator;
            // accepting it would make a malformed condition look valid and
            // then route it as false at runtime.
            if (ch == '*' || ch == '/' || ch == '%' || ch == '+') return false;
        }
        if (quoted || parentheses != 0 || brackets != 0) return false;
        String value = expression.trim();
        if (value.isBlank()) return true;
        if (value.matches(".*(?:&&|\\|\\||==|!=|>=|<=|>|<|\\bin\\b|\\bcontains\\b)\\s*$")) return false;
        if (value.matches("^(?:&&|\\|\\||==|!=|>=|<=|>|<|\\bin\\b|\\bcontains\\b).*")) return false;
        if (value.contains("&&") && !hasBinaryOperands(value, "&&")) return false;
        if (value.contains("||") && !hasBinaryOperands(value, "||")) return false;
        return true;
    }

    private static boolean hasBinaryOperands(String value, String token) {
        int split = topLevel(value, token);
        return split > 0 && split + token.length() < value.length()
                && !value.substring(0, split).trim().isBlank()
                && !value.substring(split + token.length()).trim().isBlank();
    }

    private static int topLevel(String value, String token) {
        int depth = 0; boolean quoted = false; char quote = 0;
        for (int i = 0; i <= value.length() - token.length(); i++) {
            char ch = value.charAt(i);
            if ((ch == '\'' || ch == '"') && (i == 0 || value.charAt(i - 1) != '\\')) {
                if (quoted && ch == quote) quoted = false; else if (!quoted) { quoted = true; quote = ch; }
            }
            if (!quoted) { if (ch == '(') depth++; else if (ch == ')') depth--; }
            if (!quoted && depth == 0 && value.startsWith(token, i)) return i;
        }
        return -1;
    }

    private static List<String> splitList(String value) {
        List<String> items = new ArrayList<>();
        int start = 0;
        int depth = 0;
        boolean quoted = false;
        char quote = 0;
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if ((ch == '\'' || ch == '"') && (i == 0 || value.charAt(i - 1) != '\\')) {
                if (quoted && ch == quote) quoted = false;
                else if (!quoted) { quoted = true; quote = ch; }
            } else if (!quoted) {
                if (ch == '[' || ch == '(') depth++;
                else if (ch == ']' || ch == ')') depth--;
                else if (ch == ',' && depth == 0) {
                    if (!value.substring(start, i).trim().isBlank()) items.add(value.substring(start, i).trim());
                    start = i + 1;
                }
            }
        }
        if (!value.substring(start).trim().isBlank()) items.add(value.substring(start).trim());
        return items;
    }

    private static boolean enclosesWholeExpression(String value) {
        int depth = 0;
        boolean quoted = false;
        char quote = 0;
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if ((ch == '\'' || ch == '"') && (i == 0 || value.charAt(i - 1) != '\\')) {
                if (quoted && ch == quote) quoted = false;
                else if (!quoted) { quoted = true; quote = ch; }
            }
            if (!quoted) {
                if (ch == '(') depth++;
                else if (ch == ')' && --depth == 0 && i != value.length() - 1) return false;
            }
        }
        return depth == 0;
    }
}
