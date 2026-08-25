package com.socp.search.config.api;

import com.socp.search.config.domain.FieldDef;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** API input for a normalized field definition. */
public record FieldDefRequest(
        @NotBlank @Size(max = 128)
        @Pattern(regexp = "[A-Za-z_][A-Za-z0-9_.-]*") String fieldName,
        @NotBlank @Size(max = 128) String fieldLabel,
        @NotBlank @Size(max = 32) String fieldType,
        @NotBlank @Size(max = 32) String source,
        boolean searchable,
        boolean aggregatable,
        boolean stored,
        @Size(max = 2000) String description) {

    public FieldDef toDomain() {
        return FieldDef.create(fieldName, fieldLabel, fieldType, source,
                searchable, aggregatable, stored, description);
    }
}
