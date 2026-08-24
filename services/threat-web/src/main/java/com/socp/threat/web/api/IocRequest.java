package com.socp.threat.web.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

public record IocRequest(
        @NotBlank @Pattern(regexp = "IP|DOMAIN|URL|SHA256|MD5|EMAIL") String type,
        @NotBlank @Size(max = 2048) String value,
        @Pattern(regexp = "CRITICAL|HIGH|MEDIUM|LOW|INFO") String severity,
        @Size(max = 256) String source,
        @Size(max = 4096) String description,
        @Size(max = 32) List<@Size(max = 128) String> tags) {

    public IocRequest {
        tags = tags == null ? List.of() : List.copyOf(tags);
    }
}
