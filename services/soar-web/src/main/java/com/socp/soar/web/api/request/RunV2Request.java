package com.socp.soar.web.api.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.Map;

public record RunV2Request(
        @NotBlank @Size(max = 128) String requestId,
        @NotBlank @Size(max = 64) String playbookVersionId,
        @Size(max = 8) Map<String, Object> subject,
        @NotNull @Size(max = 128) Map<String, Object> inputs) {
}
