package com.socp.alert.api.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/** Bounded alarm write batch; the idempotency key is supplied as a header. */
public record AlarmBatchRequest(
        @NotEmpty
        @Size(max = 500)
        List<@Valid CreateAlarmRequest> alarms) {
}
