package com.socp.soar.web.api.request;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Size;

/** Explicit confirmation is required before creating a new execution series. */
public record RerunV2Request(
        @Size(max = 2048) String reason,
        @AssertTrue Boolean confirm) {
}
