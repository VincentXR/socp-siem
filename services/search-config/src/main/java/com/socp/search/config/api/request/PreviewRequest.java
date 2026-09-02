package com.socp.search.config.api.request;

import jakarta.validation.constraints.Size;

public record PreviewRequest(
        @Size(max = 128) String ruleId,
        @Size(max = 32) String format,
        @Size(max = 65536) String pattern,
        @Size(max = 65536) String line) {
}
