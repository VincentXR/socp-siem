package com.socp.alert.api.request;

import com.socp.alert.domain.AlarmState;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record AlarmStatusRequest(
        @NotBlank @Pattern(regexp = AlarmState.PATTERN) String status,
        @jakarta.validation.constraints.Size(max = 4096) String reason,
        @jakarta.validation.constraints.Pattern(regexp = "TRUE_POSITIVE|FALSE_POSITIVE|BENIGN|UNDETERMINED") String classification) {
    public AlarmStatusRequest(String status) { this(status, null, null); }
}
