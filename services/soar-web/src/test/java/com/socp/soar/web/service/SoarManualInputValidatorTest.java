package com.socp.soar.web.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SoarManualInputValidatorTest {
    private final SoarManualInputValidator validator = new SoarManualInputValidator(new ObjectMapper());

    @Test
    void falseZeroAndNullableFieldsSatisfyPresenceWithoutWeakeningTypes() {
        String schema = """
                {"type":"object","required":["approved","count","reason"],"additionalProperties":false,
                 "properties":{"approved":{"type":"boolean"},"count":{"type":"integer","minimum":0},
                               "reason":{"type":["string","null"]}}}
                """;
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("approved", false); value.put("count", 0); value.put("reason", null);
        assertThatCode(() -> validator.validate(schema, value)).doesNotThrowAnyException();
        value.put("approved", "false");
        assertThatThrownBy(() -> validator.validate(schema, value)).isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("wrong type");
        value.remove("approved");
        assertThatThrownBy(() -> validator.validate(schema, value)).hasMessageContaining("required field");
    }

    @Test
    void validatesSchemasForAdditionalPropertiesAndBooleanArrayItems() {
        assertThatThrownBy(() -> validator.validate("""
                {"additionalProperties":{"type":"integer"}}
                """, Map.of("extra", "text"))).hasMessageContaining("wrong type");
        assertThatThrownBy(() -> validator.validate("""
                {"properties":{"entries":{"type":"array","items":false}}}
                """, Map.of("entries", java.util.List.of(1)))).hasMessageContaining("rejected by the form schema");
        assertThatCode(() -> validator.validate("""
                {"additionalProperties":{"type":"integer"}}
                """, Map.of("extra", 3))).doesNotThrowAnyException();
    }
}
