package com.socp.soar.web.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.socp.soar.web.definition.SoarDefinitionValidator;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;

/** Bounded manual-task input validation, independent of run and approval transactions. */
public final class SoarManualInputValidator {
    private final ObjectMapper mapper;

    public SoarManualInputValidator(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public void validate(String schemaJson, Map<String, Object> input) {
        final JsonNode schema;
        try {
            schema = mapper.readTree(schemaJson == null || schemaJson.isBlank() ? "{}" : schemaJson);
        } catch (Exception invalidSchema) {
            throw error(HttpStatus.BAD_REQUEST, "SOAR_MANUAL_INPUT_INVALID",
                    "manual task form schema is invalid");
        }
        if (schema == null || (!schema.isObject() && !schema.isBoolean())) {
            throw error(HttpStatus.BAD_REQUEST, "SOAR_MANUAL_INPUT_INVALID",
                    "manual task form schema is invalid");
        }
        if (input == null) {
            throw error(HttpStatus.BAD_REQUEST, "SOAR_MANUAL_INPUT_INVALID", "manual input is required");
        }
        // MANUAL_TASK completion is a trust boundary.  Checking only the
        // required list lets a caller submit the wrong type (for example a
        // string where a boolean approval was requested) and makes the form
        // schema decorative.  This bounded JSON-Schema subset covers the
        // fields used by SOAR forms without evaluating arbitrary schemas.
        validateManualValue(mapper.valueToTree(input), schema, "$", 0);
        if (write(input).getBytes(StandardCharsets.UTF_8).length > 64 * 1024) {
            throw error(HttpStatus.PAYLOAD_TOO_LARGE, "SOAR_MANUAL_INPUT_TOO_LARGE", "manual input exceeds 64 KiB");
        }
    }

    private void validateManualValue(JsonNode value, JsonNode schema, String path, int depth) {
        if (depth > 20) {
            throw error(HttpStatus.BAD_REQUEST, "SOAR_MANUAL_INPUT_INVALID",
                    "manual input exceeds the maximum nesting depth");
        }
        if (schema == null || schema.isNull() || schema.isMissingNode()) return;
        if (schema.isBoolean()) {
            if (!schema.asBoolean()) {
                throw error(HttpStatus.BAD_REQUEST, "SOAR_MANUAL_INPUT_INVALID",
                        "value is rejected by the form schema at " + path);
            }
            return;
        }
        if (!schema.isObject()) {
            throw error(HttpStatus.BAD_REQUEST, "SOAR_MANUAL_INPUT_INVALID",
                    "manual task form schema is invalid");
        }
        JsonNode enumValues = schema.get("enum");
        if (enumValues != null && enumValues.isArray()) {
            boolean matched = false;
            for (JsonNode allowed : enumValues) {
                if (allowed.equals(value)) { matched = true; break; }
            }
            if (!matched) {
                throw error(HttpStatus.BAD_REQUEST, "SOAR_MANUAL_INPUT_INVALID",
                        "value is not allowed at " + path);
            }
        }
        JsonNode constant = schema.get("const");
        if (constant != null && !constant.equals(value)) {
            throw error(HttpStatus.BAD_REQUEST, "SOAR_MANUAL_INPUT_INVALID",
                    "value does not match the required constant at " + path);
        }
        JsonNode declaredType = schema.get("type");
        boolean typeMatches = true;
        if (declaredType != null && declaredType.isTextual()) {
            String type = declaredType.asText("").trim().toLowerCase(Locale.ROOT);
            typeMatches = !type.isBlank() && manualTypeMatches(value, type);
        } else if (declaredType != null && declaredType.isArray()) {
            typeMatches = false;
            for (JsonNode candidate : declaredType) {
                if (candidate.isTextual() && manualTypeMatches(value,
                        candidate.asText("").trim().toLowerCase(Locale.ROOT))) {
                    typeMatches = true;
                    break;
                }
            }
        } else if (declaredType != null && !declaredType.isNull()) {
            typeMatches = false;
        }
        if (!typeMatches) {
            throw error(HttpStatus.BAD_REQUEST, "SOAR_MANUAL_INPUT_INVALID",
                    "value has the wrong type at " + path);
        }
        if (value == null || value.isNull()) return;
        if (value.isTextual()) {
            int length = value.textValue().length();
            int min = schema.path("minLength").isIntegralNumber() ? schema.path("minLength").asInt(0) : 0;
            int max = schema.path("maxLength").isIntegralNumber() ? schema.path("maxLength").asInt(64 * 1024) : 64 * 1024;
            if (length < Math.max(0, min) || length > Math.min(64 * 1024, Math.max(0, max))) {
                throw error(HttpStatus.BAD_REQUEST, "SOAR_MANUAL_INPUT_INVALID",
                        "string length is outside the allowed range at " + path);
            }
            String pattern = schema.path("pattern").asText("");
            if (!pattern.isBlank()) {
                if (pattern.length() > SoarDefinitionValidator.MAX_MANUAL_PATTERN_LENGTH
                        || !SoarDefinitionValidator.safeManualPattern(pattern)) {
                    throw error(HttpStatus.BAD_REQUEST, "SOAR_MANUAL_INPUT_INVALID",
                            "manual task form schema contains an unsafe pattern");
                }
                try {
                    java.util.regex.Pattern compiled = java.util.regex.Pattern.compile(pattern);
                    if (!compiled.matcher(value.textValue()).matches()) {
                        throw error(HttpStatus.BAD_REQUEST, "SOAR_MANUAL_INPUT_INVALID",
                                "value does not match the required pattern at " + path);
                    }
                } catch (java.util.regex.PatternSyntaxException invalid) {
                    throw error(HttpStatus.BAD_REQUEST, "SOAR_MANUAL_INPUT_INVALID",
                            "manual task form schema contains an invalid pattern");
                }
            }
        }
        if (value.isNumber()) {
            java.math.BigDecimal numeric = value.decimalValue();
            JsonNode minimum = schema.get("minimum");
            JsonNode maximum = schema.get("maximum");
            JsonNode exclusiveMinimum = schema.get("exclusiveMinimum");
            JsonNode exclusiveMaximum = schema.get("exclusiveMaximum");
            if (minimum != null && minimum.isNumber() && numeric.compareTo(minimum.decimalValue()) < 0) {
                throw error(HttpStatus.BAD_REQUEST, "SOAR_MANUAL_INPUT_INVALID",
                        "number is below the minimum at " + path);
            }
            if (maximum != null && maximum.isNumber() && numeric.compareTo(maximum.decimalValue()) > 0) {
                throw error(HttpStatus.BAD_REQUEST, "SOAR_MANUAL_INPUT_INVALID",
                        "number is above the maximum at " + path);
            }
            if (exclusiveMinimum != null && exclusiveMinimum.isNumber()
                    && numeric.compareTo(exclusiveMinimum.decimalValue()) <= 0) {
                throw error(HttpStatus.BAD_REQUEST, "SOAR_MANUAL_INPUT_INVALID",
                        "number is at or below the exclusive minimum at " + path);
            }
            if (exclusiveMaximum != null && exclusiveMaximum.isNumber()
                    && numeric.compareTo(exclusiveMaximum.decimalValue()) >= 0) {
                throw error(HttpStatus.BAD_REQUEST, "SOAR_MANUAL_INPUT_INVALID",
                        "number is at or above the exclusive maximum at " + path);
            }
        }
        if (value.isObject()) {
            JsonNode required = schema.get("required");
            if (required != null && required.isArray()) {
                for (JsonNode field : required) {
                    String name = field.asText("");
                    if (!name.isBlank() && !value.has(name)) {
                        throw error(HttpStatus.BAD_REQUEST, "SOAR_MANUAL_INPUT_INVALID",
                                "required field is missing: " + name);
                    }
                }
            }
            JsonNode properties = schema.get("properties");
            boolean rejectAdditional = schema.has("additionalProperties")
                    && schema.get("additionalProperties").isBoolean()
                    && !schema.get("additionalProperties").asBoolean();
            var fields = value.fields();
            while (fields.hasNext()) {
                var field = fields.next();
                JsonNode property = properties != null && properties.isObject()
                        ? properties.get(field.getKey()) : null;
                if (property == null && rejectAdditional) {
                    throw error(HttpStatus.BAD_REQUEST, "SOAR_MANUAL_INPUT_INVALID",
                            "unknown field at " + path + "." + field.getKey());
                }
                if (property == null && schema.path("additionalProperties").isObject()) {
                    property = schema.get("additionalProperties");
                }
                if (property != null) {
                    validateManualValue(field.getValue(), property,
                            path + "." + field.getKey(), depth + 1);
                }
            }
        } else if (value.isArray()) {
            int size = value.size();
            int min = schema.path("minItems").isIntegralNumber() ? schema.path("minItems").asInt(0) : 0;
            int max = schema.path("maxItems").isIntegralNumber() ? schema.path("maxItems").asInt(1000) : 1000;
            if (size < Math.max(0, min) || size > Math.min(1000, Math.max(0, max))) {
                throw error(HttpStatus.BAD_REQUEST, "SOAR_MANUAL_INPUT_INVALID",
                        "array length is outside the allowed range at " + path);
            }
            JsonNode items = schema.get("items");
            if (items != null && (items.isObject() || items.isBoolean())) {
                for (int index = 0; index < size; index++) {
                    validateManualValue(value.get(index), items, path + "[" + index + "]", depth + 1);
                }
            }
        }
    }

    private static boolean manualTypeMatches(JsonNode value, String type) {
        if (value == null || value.isNull()) return "null".equals(type);
        return switch (type) {
            case "object" -> value.isObject();
            case "array" -> value.isArray();
            case "string" -> value.isTextual();
            case "integer" -> value.isIntegralNumber();
            case "number" -> value.isNumber();
            case "boolean" -> value.isBoolean();
            case "null" -> value.isNull();
            default -> false;
        };
    }

    private String write(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (JsonProcessingException failure) { throw new IllegalArgumentException("cannot serialize SOAR data", failure); }
    }

    private static ResponseStatusException error(HttpStatus status, String code, String message) {
        return new ResponseStatusException(status, code + ": " + message);
    }
}
