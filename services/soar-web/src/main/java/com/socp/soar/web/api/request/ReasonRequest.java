package com.socp.soar.web.api.request;

import jakarta.validation.constraints.Size;

/** Optional operator rationale used by run-control and operational endpoints. */
public record ReasonRequest(@Size(max = 2048) String reason) {
}
