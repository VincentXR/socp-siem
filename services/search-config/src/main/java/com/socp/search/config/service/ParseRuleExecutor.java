package com.socp.search.config.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.socp.search.config.domain.ParseFormat;
import com.socp.search.config.domain.ParseRule;
import com.socp.search.config.config.SearchRuntimeRole;
import com.socp.search.config.parser.CanonicalEvent;
import com.socp.search.config.parser.ParserRegistry;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Compiles and executes one tenant-owned parsing rule.
 *
 * <p>The rule is deliberately a small, deterministic subset of a Logstash
 * filter.  Input recognition is handled by REGEX/JSON/KV or an existing
 * built-in parser; optional filters then perform safe field transformations.
 * Compiled rules are cached by {@link ParsePipelineResolver}, so the hot path
 * never recompiles a regular expression or reads the database.</p>
 */
@Component
@SearchRuntimeRole(SearchRuntimeRole.Role.API)
public class ParseRuleExecutor {

    private static final int MAX_REGEX_PATTERN_CHARS = 8 * 1024;
    private static final int MAX_REGEX_INPUT_CHARS = 256 * 1024;
    private static final int MAX_REGEX_QUANTIFIERS = 128;
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern NAMED_GROUP = Pattern.compile("\\(\\?<([A-Za-z][A-Za-z0-9_]*)>");
    private static final Pattern KV = Pattern.compile("([A-Za-z0-9_.-]+)=(\"[^\"]*\"|'[^']*'|\\S+)");
    private static final Set<String> SUPPORTED_FORMATS = Set.of(
            "REGEX", "JSON", "KV", "SYSLOG", "CEF", "LEEF", "AUTO");
    private static final Set<String> FILTER_TYPES = Set.of(
            "set", "rename", "copy", "remove", "delete", "trim", "lowercase", "uppercase", "convert");
    private static final Set<String> CONVERSION_TYPES = Set.of(
            "string", "integer", "int", "long", "double", "number", "boolean", "bool");

    private final ParserRegistry parsers;

    public ParseRuleExecutor(ParserRegistry parsers) {
        this.parsers = parsers;
    }

    public CompiledRule compile(ParseRule rule) {
        if (rule == null) throw new IllegalArgumentException("parse rule is required");
        String format = format(rule.format());
        if (!SUPPORTED_FORMATS.contains(format)) {
            throw new IllegalArgumentException("unsupported parse format: " + format);
        }
        Pattern regex = null;
        if ("REGEX".equals(format)) {
            if (rule.pattern() == null || rule.pattern().isBlank()) {
                throw new IllegalArgumentException("REGEX parse rule requires pattern");
            }
            validateRegexBudget(rule.pattern());
            regex = Pattern.compile(rule.pattern());
        }
        List<CompiledFilter> filters = new ArrayList<>();
        for (Map<String, Object> raw : rule.filters()) {
            filters.add(compileFilter(raw));
        }
        return new CompiledRule(rule, format, regex, List.copyOf(filters));
    }

    public Result execute(ParseRule rule, String input) {
        return execute(compile(rule), input);
    }

    public Result execute(CompiledRule compiled, String input) {
        if (compiled == null || input == null || input.isBlank()) {
            return Result.notMatched();
        }
        if ("REGEX".equals(compiled.format()) && input.length() > MAX_REGEX_INPUT_CHARS) {
            return new Result(false, Map.of(), "REGEX input exceeds " + MAX_REGEX_INPUT_CHARS + " characters");
        }
        try {
            Map<String, String> extracted = switch (compiled.format()) {
                case "REGEX" -> parseRegex(compiled.rule(), compiled.regex(), input);
                case "JSON" -> parseJson(input);
                case "KV" -> parseKv(input);
                case "SYSLOG", "CEF", "LEEF", "AUTO" -> parseBuiltIn(compiled.format(), input);
                default -> throw new IllegalArgumentException("unsupported parse format: " + compiled.format());
            };
            if (extracted == null) return Result.notMatched();

            applySetFields(extracted, compiled.rule().setFields());
            Map<String, String> canonical = CanonicalEvent.canonicalize(extracted);
            applyFilters(canonical, compiled.filters());
            return new Result(true, Map.copyOf(canonical), null);
        } catch (RuntimeException failure) {
            return new Result(false, Map.of(), failure.getMessage());
        }
    }

    private Map<String, String> parseBuiltIn(String format, String input) {
        Map<String, String> parsed = parsers.parse(input, ParseFormat.valueOf(format), null);
        if (parsed != null && parsed.containsKey("parse.error")) {
            throw new IllegalArgumentException(parsed.get("parse.error"));
        }
        if ("AUTO".equals(format) && parsed != null && parsed.size() == 1
                && parsed.containsKey(CanonicalEvent.EVENT_MESSAGE)
                && input.trim().equals(parsed.get(CanonicalEvent.EVENT_MESSAGE))) {
            return null;
        }
        return parsed == null || parsed.isEmpty() ? null : parsed;
    }

    private static Map<String, String> parseRegex(ParseRule rule, Pattern pattern, String input) {
        Matcher matcher = pattern.matcher(input);
        if (!matcher.find()) return null;

        Map<String, String> output = new LinkedHashMap<>();
        List<String> names = new ArrayList<>();
        Matcher named = NAMED_GROUP.matcher(rule.pattern());
        while (named.find()) names.add(named.group(1));

        if (!names.isEmpty()) {
            for (String name : names) {
                String value = matcher.group(name);
                if (value != null) output.put(mapField(rule.mapping(), name), value);
            }
        } else {
            for (ParseRule.FieldMapping mapping : rule.mapping()) {
                if (mapping.group() == null || !mapping.group().matches("\\d+")) continue;
                int index = Integer.parseInt(mapping.group());
                if (index >= 1 && index <= matcher.groupCount() && matcher.group(index) != null) {
                    output.put(mapping.field(), matcher.group(index));
                }
            }
        }
        if (output.isEmpty() && rule.setFields().isEmpty()) return null;
        return output;
    }

    private static void validateRegexBudget(String pattern) {
        if (pattern.length() > MAX_REGEX_PATTERN_CHARS) {
            throw new IllegalArgumentException("REGEX pattern exceeds " + MAX_REGEX_PATTERN_CHARS + " characters");
        }
        int quantifiers = 0;
        boolean escaped = false;
        boolean characterClass = false;
        Deque<RegexGroup> groups = new ArrayDeque<>();
        RegexAtom lastAtom = RegexAtom.NONE;
        for (int index = 0; index < pattern.length(); index++) {
            char current = pattern.charAt(index);
            if (escaped) {
                escaped = false;
                lastAtom = RegexAtom.SIMPLE;
                continue;
            }
            if (current == '\\') {
                escaped = true;
                continue;
            }
            if (current == '[' && !characterClass) {
                characterClass = true;
                lastAtom = RegexAtom.SIMPLE;
                continue;
            }
            if (current == ']' && characterClass) {
                characterClass = false;
                lastAtom = RegexAtom.SIMPLE;
                continue;
            }
            if (characterClass) continue;
            if (current == '(') {
                if (startsLookaround(pattern, index)) {
                    throw new IllegalArgumentException("REGEX lookarounds are not supported");
                }
                groups.push(new RegexGroup());
                lastAtom = RegexAtom.NONE;
                continue;
            }
            if (current == ')') {
                if (!groups.isEmpty()) {
                    RegexGroup group = groups.pop();
                    lastAtom = RegexAtom.group(group.hasQuantifier, group.hasAlternation);
                } else {
                    lastAtom = RegexAtom.SIMPLE;
                }
                continue;
            }
            if (current == '|') {
                if (!groups.isEmpty()) groups.peek().hasAlternation = true;
                lastAtom = RegexAtom.NONE;
                continue;
            }
            if (isQuantifierStart(pattern, index, current)) {
                // Java accepts '?' and '+' after a quantifier as lazy or
                // possessive modifiers; they are not a second repetition.
                if (lastAtom == RegexAtom.QUANTIFIED && (current == '?' || current == '+')) {
                    continue;
                }
                quantifiers++;
                if (lastAtom == RegexAtom.NONE) continue;
                if (lastAtom.group && (lastAtom.hasQuantifier || lastAtom.hasAlternation)) {
                    throw new IllegalArgumentException(
                            "REGEX nested or ambiguous quantified groups are not supported");
                }
                if (!groups.isEmpty()) groups.peek().hasQuantifier = true;
                lastAtom = RegexAtom.QUANTIFIED;
                if (current == '{') {
                    int close = pattern.indexOf('}', index + 1);
                    if (close > index) {
                        index = close;
                    }
                }
                continue;
            }
            if (!Character.isWhitespace(current)) lastAtom = RegexAtom.SIMPLE;
        }
        if (quantifiers > MAX_REGEX_QUANTIFIERS) {
            throw new IllegalArgumentException("REGEX pattern exceeds the quantifier budget");
        }
        // Back references make execution cost depend on captured text and are
        // not needed for field extraction. Reject them rather than allowing a
        // tenant rule to defeat the bounded linear parser path.
        if (pattern.matches("(?s).*\\\\[1-9][0-9]*.*")) {
            throw new IllegalArgumentException("REGEX back references are not supported");
        }
    }

    private static boolean isQuantifierStart(String pattern, int index, char current) {
        if (current == '*' || current == '+') return true;
        if (current == '?') return index == 0 || pattern.charAt(index - 1) != '(';
        return current == '{' && index + 1 < pattern.length()
                && Character.isDigit(pattern.charAt(index + 1));
    }

    private static boolean startsLookaround(String pattern, int index) {
        return pattern.startsWith("(?=", index) || pattern.startsWith("(?!", index)
                || pattern.startsWith("(?<=", index) || pattern.startsWith("(?<!", index);
    }

    private static final class RegexGroup {
        private boolean hasQuantifier;
        private boolean hasAlternation;
    }

    private static final class RegexAtom {
        private static final RegexAtom NONE = new RegexAtom(false, false, false);
        private static final RegexAtom SIMPLE = new RegexAtom(false, false, false);
        private static final RegexAtom QUANTIFIED = new RegexAtom(false, false, false);
        private final boolean group;
        private final boolean hasQuantifier;
        private final boolean hasAlternation;

        private RegexAtom(boolean group, boolean hasQuantifier, boolean hasAlternation) {
            this.group = group;
            this.hasQuantifier = hasQuantifier;
            this.hasAlternation = hasAlternation;
        }

        private static RegexAtom group(boolean hasQuantifier, boolean hasAlternation) {
            return new RegexAtom(true, hasQuantifier, hasAlternation);
        }
    }

    private static Map<String, String> parseJson(String input) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> raw = MAPPER.readValue(input, Map.class);
            Map<String, String> flattened = new LinkedHashMap<>();
            flatten("", raw, flattened);
            Object message = raw.get("message");
            if (message instanceof String nestedText
                    && nestedText.stripLeading().startsWith("{")) {
                try {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> nested = MAPPER.readValue(nestedText, Map.class);
                    flatten("", nested, flattened);
                } catch (Exception ignored) {
                    // Keep the valid outer JSON when message is plain text or malformed JSON.
                }
            }
            return flattened;
        } catch (Exception failure) {
            throw new IllegalArgumentException("JSON parse failed: " + failure.getMessage(), failure);
        }
    }

    private static void flatten(String prefix, Map<String, Object> values, Map<String, String> output) {
        values.forEach((key, value) -> {
            String fullKey = prefix.isBlank() ? key : prefix + "." + key;
            if (value instanceof Map<?, ?> nested) {
                Map<String, Object> child = new LinkedHashMap<>();
                nested.forEach((nestedKey, nestedValue) -> child.put(String.valueOf(nestedKey), nestedValue));
                flatten(fullKey, child, output);
            } else if (value != null) {
                output.put(fullKey, String.valueOf(value));
            }
        });
    }

    private static Map<String, String> parseKv(String input) {
        Map<String, String> output = new LinkedHashMap<>();
        Matcher matcher = KV.matcher(input);
        while (matcher.find()) {
            String value = matcher.group(2);
            if ((value.startsWith("\"") && value.endsWith("\""))
                    || (value.startsWith("'") && value.endsWith("'"))) {
                value = value.substring(1, value.length() - 1);
            }
            output.put(matcher.group(1), value);
        }
        return output.isEmpty() ? null : output;
    }

    private static void applySetFields(Map<String, String> fields, List<ParseRule.FieldMapping> setFields) {
        for (ParseRule.FieldMapping mapping : setFields) {
            if (mapping.field() != null && mapping.value() != null) {
                fields.put(mapping.field(), mapping.value());
            }
        }
    }

    private static String mapField(List<ParseRule.FieldMapping> mapping, String group) {
        for (ParseRule.FieldMapping item : mapping) {
            if (group.equals(item.group()) && item.field() != null && !item.field().isBlank()) {
                return item.field();
            }
        }
        return group;
    }

    private static CompiledFilter compileFilter(Map<String, Object> raw) {
        if (raw == null) throw new IllegalArgumentException("parse filter cannot be null");
        String type = text(raw, "type").toLowerCase(Locale.ROOT);
        if (!FILTER_TYPES.contains(type)) {
            throw new IllegalArgumentException("unsupported parse filter: " + type);
        }
        return switch (type) {
            case "set" -> new CompiledFilter(type, required(raw, "field"), null,
                    text(raw, "value"));
            case "rename", "copy" -> new CompiledFilter(type,
                    first(raw, "from", "field"), first(raw, "to", "target"), null);
            case "remove", "delete", "trim", "lowercase", "uppercase" ->
                    new CompiledFilter(type, required(raw, "field"), null, null);
            case "convert" -> {
                String targetType = first(raw, "to", "type").toLowerCase(Locale.ROOT);
                if (!CONVERSION_TYPES.contains(targetType)) {
                    throw new IllegalArgumentException("unsupported conversion type: " + targetType);
                }
                yield new CompiledFilter(type, required(raw, "field"), targetType, null);
            }
            default -> throw new IllegalArgumentException("unsupported parse filter: " + type);
        };
    }

    private static void applyFilters(Map<String, String> fields, List<CompiledFilter> filters) {
        for (CompiledFilter filter : filters) {
            String field = canonicalField(filter.field());
            switch (filter.type()) {
                case "set" -> fields.put(field, filter.value());
                case "rename" -> {
                    String value = fields.remove(field);
                    if (value != null) fields.put(canonicalField(filter.target()), value);
                }
                case "copy" -> {
                    String value = fields.get(field);
                    if (value != null) fields.put(canonicalField(filter.target()), value);
                }
                case "remove", "delete" -> fields.remove(field);
                case "trim" -> transform(fields, field, String::trim);
                case "lowercase" -> transform(fields, field, value -> value.toLowerCase(Locale.ROOT));
                case "uppercase" -> transform(fields, field, value -> value.toUpperCase(Locale.ROOT));
                case "convert" -> convert(fields, field, filter.target());
                default -> throw new IllegalArgumentException("unsupported parse filter: " + filter.type());
            }
        }
    }

    private static void transform(Map<String, String> fields, String field,
                                  java.util.function.UnaryOperator<String> operation) {
        String value = fields.get(field);
        if (value != null) fields.put(field, operation.apply(value));
    }

    private static void convert(Map<String, String> fields, String field, String targetType) {
        String value = fields.get(field);
        if (value == null) return;
        String type = targetType == null ? "string" : targetType.toLowerCase(Locale.ROOT);
        switch (type) {
            case "string" -> fields.put(field, value);
            case "integer", "int" -> fields.put(field, String.valueOf(Integer.parseInt(value.trim())));
            case "long" -> fields.put(field, String.valueOf(Long.parseLong(value.trim())));
            case "double", "number" -> fields.put(field, String.valueOf(Double.parseDouble(value.trim())));
            case "boolean", "bool" -> {
                if (!"true".equalsIgnoreCase(value.trim()) && !"false".equalsIgnoreCase(value.trim())) {
                    throw new IllegalArgumentException("field " + field + " is not boolean");
                }
                fields.put(field, String.valueOf(Boolean.parseBoolean(value.trim())));
            }
            default -> throw new IllegalArgumentException("unsupported conversion type: " + targetType);
        }
    }

    private static String canonicalField(String field) {
        if (field == null || field.isBlank()) throw new IllegalArgumentException("parse filter field is required");
        Map<String, String> probe = CanonicalEvent.canonicalize(Map.of(field, "value"));
        return probe.keySet().iterator().next();
    }

    private static String format(String value) {
        return value == null || value.isBlank() ? "AUTO" : value.trim().toUpperCase(Locale.ROOT);
    }

    private static String text(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static String required(Map<String, Object> map, String key) {
        String value = text(map, key);
        if (value.isBlank()) throw new IllegalArgumentException("parse filter requires " + key);
        return value;
    }

    private static String first(Map<String, Object> map, String primary, String secondary) {
        String value = text(map, primary);
        return value.isBlank() ? required(map, secondary) : value;
    }

    public record CompiledRule(ParseRule rule, String format, Pattern regex, List<CompiledFilter> filters) {
    }

    private record CompiledFilter(String type, String field, String target,
                                  String value) {
    }

    public record Result(boolean matched, Map<String, String> fields, String error) {
        static Result notMatched() {
            return new Result(false, Map.of(), null);
        }
    }
}
