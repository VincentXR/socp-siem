package com.socp.soar.web.api.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

public record CreateV2PlaybookRequest(
        @NotBlank @Size(max = 128) String name,
        @Size(max = 2048) String description,
        @Size(max = 32) List<@NotBlank @Size(max = 64) String> tags) {
}
