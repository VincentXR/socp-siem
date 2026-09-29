package com.socp.notify.web.api.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Explicit operator decision propagated by alert-web for a terminal notification replay. */
public record NotificationRecoveryRequest(
        @NotBlank @Size(max = 255) String alarmId,
        @NotBlank @Size(max = 512) String reason,
        boolean confirmUnknown
) { }
