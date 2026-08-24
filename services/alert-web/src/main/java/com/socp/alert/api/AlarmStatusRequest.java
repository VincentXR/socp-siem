package com.socp.alert.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record AlarmStatusRequest(
        @NotBlank @Pattern(regexp = "OPEN|INVESTIGATING|RESOLVED|CLOSED") String status) {
}
