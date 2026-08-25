package com.socp.search.config.api;

import com.socp.search.config.domain.ParseRule;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/** API input for parser rules; persistence id and timestamp are server-owned. */
public record ParseRuleRequest(
        @NotBlank @Size(max = 128) String name,
        @Size(max = 64) String sourceId,
        @NotBlank @Size(max = 32) String format,
        @Size(max = 65536) String pattern,
        @Valid @Size(max = 1000) List<@Valid FieldMapping> mapping,
        @Valid @Size(max = 1000) List<@Valid FieldMapping> setFields,
        boolean enabled,
        @Min(0) @Max(100000) int order) {

    public ParseRule toDomain() {
        return ParseRule.create(name, sourceId, format, pattern,
                toDomainMappings(mapping), toDomainMappings(setFields), enabled, order);
    }

    private static List<ParseRule.FieldMapping> toDomainMappings(List<FieldMapping> values) {
        return values == null ? List.of() : values.stream()
                .map(value -> new ParseRule.FieldMapping(value.group(), value.field(), value.value()))
                .toList();
    }

    public record FieldMapping(
            @NotBlank @Size(max = 128) String group,
            @NotBlank @Size(max = 128) String field,
            @Size(max = 4096) String value) {
    }
}
