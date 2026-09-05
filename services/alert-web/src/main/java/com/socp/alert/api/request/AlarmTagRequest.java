package com.socp.alert.api.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** A bounded analyst/automation disposition tag. */
public record AlarmTagRequest(
        @NotBlank @Size(max = 64) String tag) {
}
