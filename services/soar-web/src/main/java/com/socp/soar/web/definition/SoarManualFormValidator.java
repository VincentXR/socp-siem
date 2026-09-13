package com.socp.soar.web.definition;

import com.fasterxml.jackson.databind.JsonNode;
import com.socp.soar.web.domain.DefinitionIssue;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Set;

/** Validates the deliberately bounded JSON-Schema subset used by MANUAL_TASK. */
final class SoarManualFormValidator {

    private SoarManualFormValidator() {
    }

    /**
     * A published workflow must not advertise a form that the runtime can
     * only reject after an analyst has been asked to fill it in. Full
     * JSON-Schema (refs, scripts, unevaluated properties) is intentionally
     * outside the SOAR trust boundary.
     */
    static void validate(JsonNode schema, String path, List<DefinitionIssue> errors, int depth) {
        if (depth > 20) {
            errors.add(DefinitionIssue.error("MANUAL_FORM_INVALID", null, path,
                    "manual form schema exceeds the maximum nesting depth"));
            return;
        }
        if (schema == null || !schema.isObject()) {
            errors.add(DefinitionIssue.error("MANUAL_FORM_INVALID", null, path,
                    "manual form schema must be an object"));
            return;
        }
        JsonNode type = schema.get("type");
        if (type != null && !type.isTextual() && !type.isArray()) {
            errors.add(DefinitionIssue.error("MANUAL_FORM_INVALID", null, path + "/type",
                    "manual form type must be a string or an array of strings"));
        }
        if (type != null && type.isTextual() && !validType(type.asText())) {
            errors.add(DefinitionIssue.error("MANUAL_FORM_INVALID", null, path + "/type",
                    "manual form type is not supported"));
        }
        if (type != null && type.isArray()) {
            if (type.isEmpty() || type.size() > 8) {
                errors.add(DefinitionIssue.error("MANUAL_FORM_INVALID", null, path + "/type",
                        "manual form type array must contain 1..8 values"));
            }
            for (JsonNode item : type) {
                if (!item.isTextual() || !validType(item.asText())) {
                    errors.add(DefinitionIssue.error("MANUAL_FORM_INVALID", null, path + "/type",
                            "manual form type array contains an unsupported value"));
                    break;
                }
            }
        }
        JsonNode required = schema.get("required");
        if (required != null) {
            if (!required.isArray() || required.size() > 64) {
                errors.add(DefinitionIssue.error("MANUAL_FORM_INVALID", null, path + "/required",
                        "required must be an array of at most 64 field names"));
            } else {
                for (JsonNode item : required) {
                    if (!item.isTextual() || item.asText().isBlank() || item.asText().length() > 128) {
                        errors.add(DefinitionIssue.error("MANUAL_FORM_INVALID", null, path + "/required",
                                "required contains an invalid field name"));
                        break;
                    }
                }
            }
        }
        JsonNode properties = schema.get("properties");
        if (properties != null) {
            if (!properties.isObject() || properties.size() > 64) {
                errors.add(DefinitionIssue.error("MANUAL_FORM_INVALID", null, path + "/properties",
                        "properties must be an object with at most 64 fields"));
            } else {
                properties.fields().forEachRemaining(field -> validate(
                        field.getValue(), path + "/properties/" + field.getKey(), errors, depth + 1));
            }
        }
        JsonNode items = schema.get("items");
        if (items != null && !items.isObject()) {
            errors.add(DefinitionIssue.error("MANUAL_FORM_INVALID", null, path + "/items",
                    "items must be an object schema"));
        } else if (items != null) {
            validate(items, path + "/items", errors, depth + 1);
        }
        validateBound(schema, "minLength", 0, 64 * 1024, path, errors);
        validateBound(schema, "maxLength", 0, 64 * 1024, path, errors);
        validateBound(schema, "minItems", 0, 1000, path, errors);
        validateBound(schema, "maxItems", 0, 1000, path, errors);
        for (String field : List.of("minimum", "maximum", "exclusiveMinimum", "exclusiveMaximum")) {
            if (schema.has(field) && !schema.get(field).isNumber()) {
                errors.add(DefinitionIssue.error("MANUAL_FORM_INVALID", null, path + "/" + field,
                        field + " must be a number"));
            }
        }
        JsonNode pattern = schema.get("pattern");
        if (pattern != null) {
            if (!pattern.isTextual() || pattern.asText().length() > SoarDefinitionValidator.MAX_MANUAL_PATTERN_LENGTH
                    || !safePattern(pattern.asText())) {
                errors.add(DefinitionIssue.error("MANUAL_FORM_INVALID", null, path + "/pattern",
                        "pattern must be a bounded, non-backtracking regular expression of at most "
                                + SoarDefinitionValidator.MAX_MANUAL_PATTERN_LENGTH + " characters"));
            } else {
                try {
                    java.util.regex.Pattern.compile(pattern.asText());
                } catch (java.util.regex.PatternSyntaxException invalid) {
                    errors.add(DefinitionIssue.error("MANUAL_FORM_INVALID", null, path + "/pattern",
                            "pattern is not a valid regular expression"));
                }
            }
        }
        JsonNode additional = schema.get("additionalProperties");
        if (additional != null && !additional.isBoolean() && !additional.isObject()) {
            errors.add(DefinitionIssue.error("MANUAL_FORM_INVALID", null, path + "/additionalProperties",
                    "additionalProperties must be a boolean or schema object"));
        }
        JsonNode enumValues = schema.get("enum");
        if (enumValues != null && (!enumValues.isArray() || enumValues.size() > 100)) {
            errors.add(DefinitionIssue.error("MANUAL_FORM_INVALID", null, path + "/enum",
                    "enum must contain at most 100 values"));
        }
    }

    /**
     * Reject Java-regex constructs that make runtime cost dependent on
     * adversarial backtracking. This keeps useful patterns such as
     * {@code ^[A-Za-z0-9_-]+$} while excluding nested quantifiers and
     * quantified alternation groups.
     */
    static boolean safePattern(String regex) {
        if (regex == null || regex.length() > SoarDefinitionValidator.MAX_MANUAL_PATTERN_LENGTH
                || regex.contains("(?")) return false;
        // [hasQuantifier, hasAlternation] for each open capturing group.
        ArrayDeque<boolean[]> groups = new ArrayDeque<>();
        boolean lastAtom = false;
        boolean lastAtomWasQuantifiedGroup = false;
        boolean lastGroupHadAlternation = false;
        int unboundedQuantifiers = 0;
        for (int index = 0; index < regex.length(); index++) {
            char current = regex.charAt(index);
            if (current == '\\') {
                if (++index >= regex.length()) return false;
                char escaped = regex.charAt(index);
                if (Character.isDigit(escaped)) return false;
                lastAtom = true;
                lastAtomWasQuantifiedGroup = false;
                lastGroupHadAlternation = false;
                continue;
            }
            if (current == '[') {
                boolean closed = false;
                for (index++; index < regex.length(); index++) {
                    char inClass = regex.charAt(index);
                    if (inClass == '\\') {
                        if (++index >= regex.length()) return false;
                    } else if (inClass == ']') {
                        closed = true;
                        break;
                    }
                }
                if (!closed) return false;
                lastAtom = true;
                lastAtomWasQuantifiedGroup = false;
                lastGroupHadAlternation = false;
                continue;
            }
            if (current == '(') {
                groups.push(new boolean[] {false, false});
                lastAtom = false;
                lastAtomWasQuantifiedGroup = false;
                lastGroupHadAlternation = false;
                continue;
            }
            if (current == ')') {
                if (groups.isEmpty()) return false;
                boolean[] group = groups.pop();
                if (!groups.isEmpty()) {
                    groups.peek()[0] |= group[0];
                    groups.peek()[1] |= group[1];
                }
                lastAtom = true;
                lastAtomWasQuantifiedGroup = group[0];
                lastGroupHadAlternation = group[1];
                continue;
            }
            if (current == '*' || current == '+' || current == '?' || current == '{') {
                if (!lastAtom || lastAtomWasQuantifiedGroup || lastGroupHadAlternation) return false;
                boolean unbounded = current == '*' || current == '+';
                if (current == '{') {
                    int end = regex.indexOf('}', index + 1);
                    if (end < 0) return false;
                    String bounds = regex.substring(index + 1, end);
                    if (!bounds.matches("\\d{1,4}(,\\d{0,4})?")) return false;
                    String[] parts = bounds.split(",", -1);
                    try {
                        int lower = Integer.parseInt(parts[0]);
                        int upper = parts.length == 1 ? lower
                                : (parts[1].isBlank() ? Integer.MAX_VALUE : Integer.parseInt(parts[1]));
                        unbounded = parts.length == 2 && parts[1].isBlank();
                        if (lower > 1000 || (!unbounded && upper > 1000)
                                || (!unbounded && upper < lower)) return false;
                    } catch (NumberFormatException invalid) {
                        return false;
                    }
                    index = end;
                }
                if (unbounded && ++unboundedQuantifiers > 1) return false;
                if (!groups.isEmpty()) groups.peek()[0] = true;
                lastAtom = false;
                lastAtomWasQuantifiedGroup = false;
                lastGroupHadAlternation = false;
                continue;
            }
            if (current == '^' || current == '$') {
                lastAtom = false;
                lastAtomWasQuantifiedGroup = false;
                lastGroupHadAlternation = false;
                continue;
            }
            if (current == '|') {
                if (!groups.isEmpty()) groups.peek()[1] = true;
                lastAtom = false;
                lastAtomWasQuantifiedGroup = false;
                lastGroupHadAlternation = false;
                continue;
            }
            lastAtom = true;
            lastAtomWasQuantifiedGroup = false;
            lastGroupHadAlternation = false;
        }
        return groups.isEmpty();
    }

    private static void validateBound(JsonNode schema, String field, int min, int max,
                                      String path, List<DefinitionIssue> errors) {
        JsonNode value = schema.get(field);
        if (value == null) return;
        if (!isIntegerValue(value) || value.asInt() < min || value.asInt() > max) {
            errors.add(DefinitionIssue.error("MANUAL_FORM_INVALID", null, path + "/" + field,
                    field + " must be an integer from " + min + " to " + max));
        }
    }

    private static boolean validType(String value) {
        return value != null && Set.of("object", "array", "string", "integer", "number",
                "boolean", "null").contains(value.trim().toLowerCase(java.util.Locale.ROOT));
    }

    private static boolean isIntegerValue(JsonNode node) {
        return node != null && node.isIntegralNumber() && node.canConvertToInt();
    }
}
