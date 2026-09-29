package com.socp.alert.api.request;

import jakarta.validation.constraints.Size;

/** Operator evidence required when a notification terminal receipt is reopened. */
public record OutboxReplayRequest(
        @Size(max = 512) String reason,
        boolean confirmUnknown
) { }
